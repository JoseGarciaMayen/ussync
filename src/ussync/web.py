from __future__ import annotations

import asyncio
import os
import re
import secrets
import tempfile
import threading
import unicodedata
import webbrowser
from contextlib import AsyncExitStack, asynccontextmanager
from dataclasses import asdict, replace
from pathlib import Path

from dotenv import dotenv_values
from fastapi import FastAPI, HTTPException, Request
from fastapi.responses import FileResponse, HTMLResponse, JSONResponse
from filelock import FileLock, Timeout
from pydantic import BaseModel, Field

from . import demo
from .config import Config, default_env
from .connectors.ev import EV, browser
from .connectors.sevius import Sevius, Subject
from .engine import Engine, excluded, target_path
from .errors import USSyncError
from .models import Document
from .network import HTTP
from .storage import Store

STATIC = Path(__file__).with_name("static")


def normalized(value):
    return "".join(
        c for c in unicodedata.normalize("NFKD", value.casefold()) if not unicodedata.combining(c)
    )


def teaching_candidate(name):
    text = normalized(name)
    return any(
        term in text
        for term in (
            "proyecto docente",
            "programa de la asignatura",
            "programa docente",
            "guia docente",
            "teaching guide",
            "syllabus",
        )
    )


def within(root, relative):
    path = (root / relative).resolve()
    if not path.is_relative_to(root.resolve()):
        raise HTTPException(400, "La ruta debe estar dentro de la biblioteca.")
    return path


class Settings(BaseModel):
    dest: str = Field(min_length=1, max_length=2048)
    concurrency: int = Field(ge=1, le=8)
    degree: str = Field(pattern=r"^[A-Za-z0-9]+$")
    centers: str = Field(pattern=r"^\d+(,\s*\d+)*$")


class CourseChoice(BaseModel):
    id: str
    folder: str = Field(default="", max_length=100)


class Courses(BaseModel):
    courses: list[CourseChoice]


class FileSelection(BaseModel):
    keys: list[str]
    excluded_keys: list[str] | None = None
    folders: list[dict] | None = None


class TeachingSelection(BaseModel):
    subject: str
    values: list[str]
    course_id: str | None = None


def create_app(config: Config, env_file=None, demo_mode=False):
    token = secrets.token_urlsafe(32)
    app_config = config
    config.state.mkdir(parents=True, exist_ok=True, mode=0o700)
    store = Store(config.state)
    task = None
    job = {"running": False, "name": "", "logs": [], "error": None, "result": None, "serial": 0}

    @asynccontextmanager
    async def lifespan(app):
        yield
        if task and not task.done():
            task.cancel()
            await asyncio.gather(task, return_exceptions=True)
        store.close()

    app = FastAPI(lifespan=lifespan, docs_url=None, redoc_url=None, openapi_url=None)
    app.state.token = token
    app.state.store = store

    def log(message):
        job["logs"].append(str(message))
        job["logs"] = job["logs"][-300:]

    def idle():
        if job["running"]:
            raise HTTPException(409, "Hay una operación en curso; espera o cancélala.")

    def start(name, operation):
        nonlocal task
        idle()
        lock = FileLock(config.state / "ussync.lock", timeout=0)
        try:
            lock.acquire()
        except Timeout:
            raise HTTPException(409, "Otra instancia está utilizando USSync.") from None
        job.update(
            running=True, name=name, logs=[], error=None, result=None, serial=job["serial"] + 1
        )

        async def run():
            try:
                job["result"] = await operation()
            except asyncio.CancelledError:
                job["error"] = "Operación cancelada. Se conservan los archivos completos."
            except Exception as exc:
                job["error"] = str(exc)
                log(f"Error: {exc}")
            finally:
                job["running"] = False
                lock.release()

        task = asyncio.create_task(run())
        return {"started": True}

    @app.middleware("http")
    async def local_only(request: Request, call_next):
        host = request.headers.get("host", "").split(":")[0]
        if host not in {"127.0.0.1", "localhost", "testserver"}:
            return JSONResponse({"detail": "Solo acceso local."}, status_code=403)
        origin = request.headers.get("origin")
        if origin and origin != f"http://{request.headers.get('host')}":
            return JSONResponse({"detail": "Origen no permitido."}, status_code=403)
        if request.url.path.startswith("/api/"):
            supplied = request.headers.get("x-ussync-token", "")
            if request.url.path == "/api/file":
                supplied = request.query_params.get("token", supplied)
            if not secrets.compare_digest(supplied, token):
                return JSONResponse(
                    {"detail": "Recarga la interfaz para renovar su sesión local."}, status_code=403
                )
        response = await call_next(request)
        response.headers["X-Content-Type-Options"] = "nosniff"
        response.headers["Referrer-Policy"] = "no-referrer"
        response.headers["Cache-Control"] = "no-store"
        response.headers["Content-Security-Policy"] = (
            "default-src 'self'; script-src 'self'; style-src 'self'; img-src 'self' data:; frame-ancestors 'none'; base-uri 'none'"
        )
        return response

    @app.exception_handler(USSyncError)
    async def domain_error(request, exc):
        return JSONResponse({"detail": str(exc)}, status_code=400)

    @app.get("/")
    async def index():
        return HTMLResponse((STATIC / "index.html").read_text().replace("__TOKEN__", token))

    @app.get("/assets/{name}")
    async def asset(name: str):
        if name not in {"app.js", "style.css", "icon.svg"}:
            raise HTTPException(404)
        return FileResponse(STATIC / name)

    @app.get("/api/state")
    async def state():
        documents = []
        for raw in store.get("scan", []):
            doc = Document(**raw)
            path = target_path(app_config.dest, doc)
            documents.append(
                {
                    **raw,
                    "url": "",
                    "form": {},
                    "selected": not excluded(doc, store),
                    "downloaded": path.is_file(),
                    "target": str(path.relative_to(app_config.dest)),
                    "teaching_candidate": teaching_candidate(" ".join(doc.parts)),
                }
            )
        return {
            "config": {
                "dest": str(app_config.dest),
                "concurrency": app_config.concurrency,
                "degree": app_config.degree,
                "centers": ",".join(app_config.centers),
            },
            "demo": demo_mode,
            "courses": store.get("available_courses", []),
            "selected_courses": store.get("ev", []),
            "documents": documents,
            "sevius": store.get("sevius", []),
            "job": job,
            "excluded_folders": store.get("excluded_folders", []),
            "preferences": store.get("preferences", {}),
            "last_run": store.last_run(),
        }

    @app.post("/api/settings")
    async def settings(body: Settings):
        nonlocal app_config
        idle()
        destination = Path(body.dest).expanduser()
        if not destination.is_absolute():
            raise HTTPException(400, "El destino debe ser una ruta absoluta.")
        destination = destination.resolve()
        if app_config.state.is_relative_to(destination):
            raise HTTPException(
                400, "La biblioteca no puede contener la carpeta privada de estado."
            )
        candidate = replace(
            app_config,
            dest=destination,
            concurrency=body.concurrency,
            degree=body.degree,
            centers=tuple(x.strip() for x in body.centers.split(",")),
        )
        if demo_mode:
            app_config = candidate
            return {"saved": True}
        path = Path(env_file).resolve() if env_file else default_env()
        values = dict(dotenv_values(path))
        values.update(
            USSYNC_DEST=str(destination),
            USSYNC_CONCURRENCY=str(body.concurrency),
            USSYNC_SEVIUS_DEGREE=body.degree,
            USSYNC_SEVIUS_CENTERS=body.centers,
        )

        # dotenv single-quoted strings preserve Windows backslashes and dollar signs.
        def quote(value):
            return "'" + str(value).replace("\\", "\\\\").replace("'", "\\'") + "'"

        path.parent.mkdir(parents=True, exist_ok=True)
        fd, tmp = tempfile.mkstemp(prefix=".env-", dir=path.parent)
        try:
            with os.fdopen(fd, "w") as stream:
                for key, value in values.items():
                    if re.fullmatch(r"[A-Za-z_][A-Za-z0-9_]*", key) and value is not None:
                        stream.write(f"{key}={quote(value)}\n")
            os.replace(tmp, path)
        finally:
            Path(tmp).unlink(missing_ok=True)
        app_config = candidate
        return {
            "saved": True,
            "message": "Guardado en .env. Los archivos anteriores no se mueven automáticamente.",
        }

    @app.post("/api/preferences")
    async def preferences(body: dict):
        allowed = {
            k: str(v)[:100]
            for k, v in body.items()
            if k in {"theme", "density", "accent", "view", "sort"}
        }
        store.set("preferences", allowed)
        return {"saved": True}

    @app.post("/api/login")
    async def login():
        async def operation():
            if demo_mode:
                courses = [
                    {
                        "id": "demo-algebra",
                        "name": "Álgebra de demostración",
                        "courseId": "0000001",
                    },
                    {
                        "id": "demo-info",
                        "name": "Informática de demostración",
                        "courseId": "0000002",
                    },
                ]
            else:
                log(
                    "Completa el login en la ventana de Chromium. No cierres la ventana hasta terminar."
                )
                async with browser(app_config, interactive=True) as ctx:
                    connector = EV(ctx, app_config.ev_url)
                    user = await connector.authenticate(interactive=True)
                    courses = await connector.courses(user["id"])
            store.set("available_courses", courses)
            log(f"Sesión preparada. {len(courses)} cursos disponibles.")
            return {"courses": len(courses)}

        return start("Iniciar sesión y consultar cursos", operation)

    @app.post("/api/courses")
    async def courses(body: Courses):
        idle()
        available = {c["id"]: c for c in store.get("available_courses", [])}
        selected = []
        for choice in body.courses:
            if choice.id not in available:
                raise HTTPException(400, "Curso desconocido; vuelve a iniciar sesión.")
            course = dict(available[choice.id])
            if choice.folder.strip():
                course["folder"] = choice.folder.strip()
            selected.append(course)
        store.set("ev", selected)
        return {"saved": True}

    @app.post("/api/scan")
    async def scan():
        async def operation():
            found = []
            if demo_mode:
                chosen = {c["name"]: c for c in store.get("ev", [])}
                async for doc in demo.documents():
                    if doc.course in chosen:
                        course = chosen[doc.course]
                        found.append(
                            asdict(
                                replace(
                                    doc,
                                    course=course.get("folder") or doc.course,
                                    size=len(demo.pdf("USSync - Documento de demostracion")),
                                )
                            )
                        )
            elif store.get("ev", []):
                async with browser(app_config) as ctx:
                    connector = EV(ctx, app_config.ev_url)
                    await connector.authenticate()
                    async for doc in connector.documents(store.get("ev"), log):
                        found.append(asdict(doc))
            if not demo_mode:
                http = HTTP()
                try:
                    sevius = Sevius(http, app_config.degree, app_config.centers)
                    for selection in store.get("sevius", []):
                        try:
                            async for doc in sevius.selected_documents([selection]):
                                found.append(asdict(doc))
                        except USSyncError as exc:
                            log(f"ERROR · SEVIUS: {exc}")
                finally:
                    await http.close()
            store.set("scan", found)
            log(
                f"Escaneo terminado: {len(found)} archivos descargables. Los tamaños desconocidos se muestran como —."
            )
            return {"files": len(found), "partial": any(x.startswith("ERROR") for x in job["logs"])}

        return start("Escanear materiales y proyectos", operation)

    @app.post("/api/selection")
    async def selection(body: FileSelection):
        idle()
        known = {d["key"] for d in store.get("scan", [])}
        if not set(body.keys).issubset(known):
            raise HTTPException(400, "La selección contiene documentos desconocidos.")
        if body.excluded_keys is not None:
            old = set(store.get("excluded_keys", [])) - known
            store.set("excluded_keys", sorted(old | (set(body.excluded_keys) & known)))
        else:
            old = set(store.get("excluded_keys", [])) - known
            store.set("excluded_keys", sorted(old | (known - set(body.keys))))
        if body.folders is not None:
            valid = []
            for rule in body.folders:
                if (
                    isinstance(rule.get("course"), str)
                    and isinstance(rule.get("parts"), list)
                    and all(isinstance(p, str) for p in rule["parts"])
                ):
                    valid.append({"course": rule["course"], "parts": rule["parts"]})
            store.set("excluded_folders", valid)
        return {"saved": True}

    @app.post("/api/download")
    async def download(body: FileSelection):
        known = {d["key"]: d for d in store.get("scan", [])}
        if not body.keys or not set(body.keys).issubset(known):
            raise HTTPException(400, "Selecciona al menos un archivo del escaneo.")
        documents = [Document(**known[key]) for key in dict.fromkeys(body.keys)]

        async def operation():
            async with AsyncExitStack() as stack:
                public, private = HTTP(), HTTP()
                stack.push_async_callback(public.close)
                stack.push_async_callback(private.close)
                transports = {"ev": private, "sevius": public}
                if demo_mode:
                    synthetic = demo.transport()
                    stack.push_async_callback(synthetic.close)
                    transports["demo"] = synthetic
                if any(d.source == "ev" for d in documents):
                    ctx = await stack.enter_async_context(browser(app_config))
                    ev = EV(ctx, app_config.ev_url)
                    await ev.authenticate()
                    await ev.transfer_cookies(private)

                async def generate():
                    for document in documents:
                        yield document

                result = await Engine(app_config, store, transports, log).run(generate())
                log(
                    f"Terminado: {result.downloaded} nuevos, {result.updated} actualizados, {result.unchanged} sin cambios, {result.errors} errores, {result.conflicts} conflictos."
                )
                return asdict(result)

        return start("Descargar selección", operation)

    @app.post("/api/cancel")
    async def cancel():
        if task and not task.done():
            task.cancel()
        return {"cancelled": True}

    @app.get("/api/sevius/subjects")
    async def subjects():
        http = HTTP()
        try:
            result = await Sevius(http, app_config.degree, app_config.centers).subjects()
            store.set("sevius_subjects", [asdict(s) for s in result])
            return [asdict(s) for s in result]
        finally:
            await http.close()

    @app.get("/api/sevius/documents/{code}")
    async def teaching_documents(code: str):
        subject = next((s for s in store.get("sevius_subjects", []) if s["code"] == code), None)
        if not subject:
            raise HTTPException(404, "Elige una asignatura del catálogo.")
        http = HTTP()
        try:
            documents = await Sevius(http, app_config.degree, app_config.centers).documents(
                Subject(**subject)
            )
            store.set(f"sevius_documents:{code}", [asdict(d) for d in documents])
            return [asdict(d) for d in documents]
        finally:
            await http.close()

    @app.post("/api/sevius/selection")
    async def teaching_selection(body: TeachingSelection):
        idle()
        subject = next(
            (s for s in store.get("sevius_subjects", []) if s["code"] == body.subject), None
        )
        if not subject:
            raise HTTPException(400, "Asignatura desconocida.")
        available = store.get(f"sevius_documents:{body.subject}", [])
        if not set(body.values).issubset({d["value"] for d in available}):
            raise HTTPException(400, "Proyecto desconocido; vuelve a consultar la asignatura.")
        chosen = {
            "subject": subject,
            "documents": [d for d in available if d["value"] in body.values],
        }
        if body.course_id:
            course = next((c for c in store.get("ev", []) if c["id"] == body.course_id), None)
            if not course:
                raise HTTPException(400, "El curso asociado no está seleccionado.")
            chosen["folder"] = course.get("folder") or f"{course['name']} [{course['id']}]"
        selections = [s for s in store.get("sevius", []) if s["subject"]["code"] != body.subject]
        if chosen["documents"]:
            selections.append(chosen)
        store.set("sevius", selections)
        return {"saved": True}

    @app.get("/api/library")
    async def library(path: str = "", q: str = ""):
        root = app_config.dest
        folder = within(root, path)
        if not folder.exists():
            return {"entries": [], "path": path, "truncated": False}
        if not folder.is_dir():
            raise HTTPException(400, "La ruta no es una carpeta.")
        entries = []
        iterator = folder.rglob("*") if q else folder.iterdir()
        for item in iterator:
            if (
                item.is_symlink()
                or item.name.startswith(".")
                or any(p.startswith(".") for p in item.relative_to(root).parts)
            ):
                continue
            if q and normalized(q) not in normalized(str(item.relative_to(root))):
                continue
            try:
                info = item.stat()
                entries.append(
                    {
                        "name": item.name,
                        "path": str(item.relative_to(root)),
                        "directory": item.is_dir(),
                        "size": info.st_size if item.is_file() else None,
                    }
                )
            except OSError:
                continue
            if len(entries) >= 1000:
                break
        return {
            "entries": sorted(entries, key=lambda e: (not e["directory"], e["name"].casefold())),
            "path": path,
            "truncated": len(entries) >= 1000,
        }

    @app.get("/api/file")
    async def file(path: str):
        location = within(app_config.dest, path)
        if not location.is_file():
            raise HTTPException(404, "No existe el archivo.")
        return FileResponse(
            location,
            filename=location.name,
            content_disposition_type="inline"
            if location.suffix.lower() == ".pdf"
            else "attachment",
        )

    return app


def serve(config, env_file=None, port=8766, no_browser=False, demo_mode=False):
    import uvicorn

    if demo_mode:
        config = replace(config, dest=Path.cwd() / "demo-library", state=Path.cwd() / "demo-state")
    app = create_app(config, env_file, demo_mode)
    if not no_browser:
        timer = threading.Timer(1, webbrowser.open, args=[f"http://127.0.0.1:{port}"])
        timer.daemon = True
        timer.start()
    print(f"USSync: http://127.0.0.1:{port} · Ctrl+C para cerrar")
    uvicorn.run(app, host="127.0.0.1", port=port, log_level="warning", access_log=False)
