from __future__ import annotations

import argparse
import asyncio
import sys
from contextlib import AsyncExitStack
from dataclasses import asdict, replace
from pathlib import Path

import questionary
from filelock import FileLock, Timeout

from . import __version__, demo, ui
from .config import Config
from .connectors.ev import EV, browser
from .connectors.sevius import Sevius
from .engine import Engine
from .errors import Cancelled, USSyncError
from .network import HTTP
from .storage import Store


def parser():
    root = argparse.ArgumentParser(
        prog="ussync", description="Tu biblioteca de Enseñanza Virtual y SEVIUS."
    )
    root.add_argument("--version", action="version", version=__version__)
    root.add_argument("--env-file", type=Path)
    root.add_argument("--dest", type=Path, help="Carpeta de descargas (sobrescribe .env)")
    root.add_argument("--state-dir", type=Path, help="Carpeta privada de estado")
    root.add_argument("--concurrency", type=int, choices=range(1, 9))
    sub = root.add_subparsers(dest="command")
    sub.add_parser("login", help="Abrir el navegador y guardar la sesión de Enseñanza Virtual")
    for name, help_text in (
        ("select", "Elegir asignaturas, año y grupos"),
        ("sync", "Descargar lo seleccionado"),
    ):
        child = sub.add_parser(name, help=help_text)
        child.add_argument("--source", choices=("all", "ev", "sevius"), default="all")
        if name == "sync":
            child.add_argument(
                "--non-interactive",
                action="store_true",
                help="No abrir selectores si falta configuración",
            )
    sub.add_parser("status", help="Mostrar destino, selecciones y última sincronización")
    sub.add_parser("demo", help="Probar con PDF ficticios en ./demo-library y estado aislado")
    panel = sub.add_parser("ui", help="Abrir la interfaz local en el navegador")
    panel.add_argument("--port", type=int, default=8766)
    panel.add_argument("--no-browser", action="store_true")
    panel.add_argument("--demo", action="store_true", help="Datos ficticios y biblioteca aislada")
    return root


async def select(config, store, source):
    ev_selected = store.get("ev", [])
    sevius_selected = store.get("sevius", [])
    if source == "all":
        sources = await ui.ask(
            questionary.checkbox(
                "Fuentes que quieres configurar",
                choices=[
                    questionary.Choice("Enseñanza Virtual (requiere login)", "ev", checked=True),
                    questionary.Choice(
                        "SEVIUS: programas y proyectos (sin login)", "sevius", checked=True
                    ),
                ],
            )
        )
    else:
        sources = [source]
    if "ev" in sources:
        ui.console.print(
            "Se abrirá el navegador. Completa el login de la universidad si se solicita."
        )
        async with browser(config, interactive=True) as ctx:
            connector = EV(ctx, config.ev_url)
            user = await connector.authenticate(interactive=True)
            ev_selected = await ui.select_ev(connector, user, ev_selected)
    if "sevius" in sources:
        http = HTTP()
        try:
            sevius_selected = await ui.select_sevius(
                Sevius(http, config.degree, config.centers), sevius_selected
            )
        finally:
            await http.close()
    if sources:
        await ui.associate(ev_selected, sevius_selected)
        store.set("ev", ev_selected)
        store.set("sevius", sevius_selected)
        ui.console.print("Selección guardada. Ejecuta «ussync sync» para descargar.")


async def synchronize(config, store, source="all", non_interactive=False):
    if not store.get("ev", []) and not store.get("sevius", []):
        if non_interactive:
            raise USSyncError("No hay asignaturas seleccionadas. Ejecuta «ussync select» primero.")
        await select(config, store, source)
    selected_ev = store.get("ev", []) if source in {"all", "ev"} else []
    selected_sevius = store.get("sevius", []) if source in {"all", "sevius"} else []
    if not selected_ev and not selected_sevius:
        raise USSyncError(
            "No hay documentos seleccionados para esta fuente. Ejecuta «ussync select»."
        )
    discovery_errors = 0

    def report(message):
        nonlocal discovery_errors
        if message.startswith("ERROR ·"):
            discovery_errors += 1
        ui.console.print(message)

    async with AsyncExitStack() as stack:
        sevius_http, ev_http = HTTP(), HTTP()
        stack.push_async_callback(sevius_http.close)
        stack.push_async_callback(ev_http.close)
        ev = None
        if selected_ev:
            try:
                ctx = await stack.enter_async_context(browser(config))
                ev = EV(ctx, config.ev_url)
                await ev.authenticate()
                await ev.transfer_cookies(ev_http)
            except USSyncError as exc:
                report(f"ERROR · Enseñanza Virtual: {exc}")
                ev = None
        sevius = Sevius(sevius_http, config.degree, config.centers)

        async def documents():
            if ev:
                try:
                    async for doc in ev.documents(selected_ev, report):
                        yield doc
                except USSyncError as exc:
                    report(f"ERROR · Enseñanza Virtual: {exc}")
            for selection in selected_sevius:
                try:
                    async for doc in sevius.selected_documents([selection]):
                        yield doc
                except USSyncError as exc:
                    report(f"ERROR · SEVIUS / {selection['subject']['name']}: {exc}")

        engine = Engine(config, store, {"ev": ev_http, "sevius": sevius_http}, report)
        result = await engine.run(documents())
        result.errors += discovery_errors
        last = store.last_run()
        if last:
            store.finish_run(last["id"], {**asdict(result), "completed": True})
        ui.summary(result)
        return 1 if result.errors or result.conflicts else 0


async def execute(args, config, store):
    command = args.command
    if command is None:
        ui.console.print("USSync · Tu biblioteca universitaria")
        command = await ui.ask(
            questionary.select(
                "¿Qué quieres hacer?",
                choices=[
                    questionary.Choice("Elegir asignaturas y grupos", "select"),
                    questionary.Choice("Sincronizar documentos", "sync"),
                    questionary.Choice("Iniciar sesión en Enseñanza Virtual", "login"),
                    questionary.Choice("Ver estado", "status"),
                    questionary.Choice("Salir", "exit"),
                ],
            )
        )
    if command == "login":
        ui.console.print("Completa el acceso en el navegador. Esperaremos hasta cinco minutos.")
        async with browser(config, interactive=True) as ctx:
            await EV(ctx, config.ev_url).authenticate(interactive=True)
        ui.console.print("Sesión guardada. Ya puedes ejecutar «ussync select» o «ussync sync».")
    elif command == "select":
        await select(config, store, getattr(args, "source", "all"))
    elif command == "sync":
        return await synchronize(
            config, store, getattr(args, "source", "all"), getattr(args, "non_interactive", False)
        )
    elif command == "status":
        ui.status(config, store)
    elif command == "demo":
        http = demo.transport()
        try:
            ui.console.print(f"Demo aislada en {config.dest}")
            result = await Engine(config, store, {"demo": http}, ui.console.print).run(
                demo.documents()
            )
            ui.summary(result)
            return int(bool(result.errors or result.conflicts))
        finally:
            await http.close()
    return 0


def main(argv=None):
    args = parser().parse_args(argv)
    try:
        config = Config.load(args.env_file, args.dest, args.state_dir, args.concurrency)
        if args.command == "ui":
            from .web import serve

            serve(config, args.env_file, args.port, args.no_browser, args.demo)
            return
        if args.command == "demo":
            config = replace(
                config,
                dest=(args.dest or Path.cwd() / "demo-library").resolve(),
                state=(args.state_dir or Path.cwd() / "demo-state").resolve(),
            )
            if config.state.is_relative_to(config.dest):
                raise USSyncError("La carpeta de estado de demo debe estar fuera de su biblioteca.")
        config.state.mkdir(parents=True, exist_ok=True, mode=0o700)
        with FileLock(config.state / "ussync.lock", timeout=0):
            store = Store(config.state)
            try:
                code = asyncio.run(execute(args, config, store))
            finally:
                store.close()
    except Timeout:
        ui.console.print("Ya hay una operación de USSync usando este estado. Espera a que termine.")
        code = 2
    except (Cancelled, KeyboardInterrupt):
        ui.console.print("Operación cancelada.")
        code = 130
    except (USSyncError, OSError) as exc:
        ui.console.print(f"Error: {exc}")
        code = 1
    sys.exit(code)
