from __future__ import annotations

import sys

import questionary
from questionary import Choice
from rich.console import Console
from rich.table import Table

from .connectors.sevius import Subject
from .errors import Cancelled, USSyncError

console = Console(markup=False, highlight=False)


async def ask(question):
    if not sys.stdin.isatty():
        raise USSyncError(
            "El selector necesita una terminal interactiva. Usa «ussync sync --non-interactive» si ya has configurado las asignaturas."
        )
    result = await question.ask_async()
    if result is None:
        raise Cancelled("Selección cancelada; no se han cambiado las preferencias.")
    return result


async def select_sevius(connector, previous):
    console.print("Consultando asignaturas de SEVIUS…")
    subjects = await connector.subjects()
    if not subjects:
        raise USSyncError("No hay asignaturas para los centros y la titulación configurados.")
    old = {s["subject"]["code"]: s for s in previous}
    selected = await ask(
        questionary.checkbox(
            "Asignaturas de SEVIUS (↑↓, espacio para marcar, Enter para continuar)",
            choices=[Choice(f"{s.name} · {s.code}", s, checked=s.code in old) for s in subjects],
        )
    )
    selections = []
    for subject in selected:
        console.print(f"Consultando proyectos · {subject.name}")
        documents = await connector.documents(subject)
        projects = [d for d in documents if d.kind == "proyecto"]
        years = sorted({d.year for d in projects}, reverse=True)
        if years:
            year = await ask(questionary.select(f"Curso académico · {subject.name}", choices=years))
            candidates = [d for d in projects if d.year == year]
            old_values = {d["value"] for d in old.get(subject.code, {}).get("documents", [])}
            picked = await ask(
                questionary.checkbox(
                    f"Proyectos/grupos · {subject.name} (incluye su programa asociado)",
                    choices=[Choice(d.label, d, checked=d.value in old_values) for d in candidates],
                )
            )
        else:
            console.print("No hay proyectos publicados. Puedes elegir un programa.")
            picked = await ask(
                questionary.checkbox(
                    f"Programas · {subject.name}",
                    choices=[Choice(d.label, d) for d in documents if d.kind == "programa"],
                )
            )
        if picked:
            selection = connector.selection(subject, picked)
            if old.get(subject.code, {}).get("folder"):
                selection["folder"] = old[subject.code]["folder"]
            selections.append(selection)
    return selections


async def select_ev(connector, user, previous):
    console.print("Consultando tus cursos de Enseñanza Virtual…")
    courses = await connector.courses(user["id"])
    old = {c["id"]: c for c in previous}
    if not courses:
        raise USSyncError("La cuenta no muestra cursos accesibles.")
    selected = await ask(
        questionary.checkbox(
            "Cursos de Enseñanza Virtual",
            choices=[Choice(c["name"], c, checked=c["id"] in old) for c in courses],
        )
    )
    for course in selected:
        if old.get(course["id"], {}).get("folder"):
            course["folder"] = old[course["id"]]["folder"]
    return selected


async def associate(ev_courses, sevius):
    if not ev_courses or not sevius:
        return
    console.print("Relaciona las dos fuentes para reunir sus documentos en la misma carpeta.")
    used = set()
    for selection in sevius:
        subject = Subject(**selection["subject"])
        available = [c for c in ev_courses if c["id"] not in used]
        match = next(
            (
                c
                for c in available
                if subject.code in c.get("courseId", "") or subject.code in c["name"]
            ),
            None,
        )
        choices = [Choice("Mantener separada", None)] + [
            Choice(c["name"], c["id"]) for c in available
        ]
        chosen = await ask(
            questionary.select(
                f"Curso de Enseñanza Virtual para {subject.name}",
                choices=choices,
                default=match["id"] if match else None,
            )
        )
        if chosen:
            folder = f"{subject.name} [{subject.code}]"
            next(c for c in ev_courses if c["id"] == chosen)["folder"] = folder
            selection["folder"] = folder
            used.add(chosen)


def status(config, store):
    console.print(f"Destino: {config.dest}\nEstado privado: {config.state}")
    table = Table("Fuente", "Asignaturas", "Selección")
    ev = store.get("ev", [])
    sevius = store.get("sevius", [])
    table.add_row(
        "Enseñanza Virtual", str(len(ev)), ", ".join(c["name"] for c in ev) or "Sin configurar"
    )
    table.add_row(
        "SEVIUS",
        str(len(sevius)),
        ", ".join(s["subject"]["name"] for s in sevius) or "Sin configurar",
    )
    console.print(table)
    run = store.last_run()
    if run:
        console.print(
            f"Última ejecución: {run['started']}\nResultado: {run['summary'] or 'Interrumpida o en curso'}"
        )


def summary(result):
    console.print(
        f"\n{result.downloaded} nuevos · {result.updated} actualizados · "
        f"{result.unchanged} sin cambios · {result.reused} reutilizados · "
        f"{result.conflicts} conflictos · {result.errors} errores\n"
        f"Transferido: {result.bytes / 1024 / 1024:.2f} MiB"
    )
