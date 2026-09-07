from __future__ import annotations

import asyncio
import hashlib
import os
import shutil
import tempfile
from dataclasses import asdict
from pathlib import Path

from .errors import RemoteError, SessionExpired
from .models import Summary
from .storage import digest, slug


def target_path(root: Path, doc) -> Path:
    # Stable suffixes prevent case-insensitive and sanitized-name collisions.
    parts = [slug(p) for p in doc.parts]
    filename = Path(parts[-1])
    token = hashlib.sha256(doc.key.encode()).hexdigest()[:10]
    parts[-1] = f"{filename.stem} [{token}]{filename.suffix}"
    candidate = root / slug(doc.course) / Path(*parts)
    if not candidate.resolve().is_relative_to(root.resolve()):
        raise RemoteError("La ruta de destino sale de la biblioteca (posible enlace simbólico).")
    return candidate


def validate(path, doc, headers):
    with path.open("rb") as stream:
        head = stream.read(1024).lstrip()
    if not head:
        raise RemoteError("La fuente devolvió un archivo vacío.")
    if doc.pdf or path.suffix.lower() == ".pdf" or doc.parts[-1].lower().endswith(".pdf"):
        if not head.startswith(b"%PDF-"):
            raise RemoteError(
                "Se esperaba un PDF pero la respuesta no es un PDF (posible página de login)."
            )
    elif "text/html" in headers.get("content-type", "").lower() and not doc.parts[
        -1
    ].lower().endswith((".html", ".htm")):
        raise RemoteError("La fuente devolvió HTML en lugar del archivo; comprueba la sesión.")


class Engine:
    def __init__(self, config, store, transports, report=print):
        self.config, self.store, self.transports = config, store, transports
        self.report = report
        self.summary = Summary()
        self.auth_failed = False

    async def run(self, documents):
        run_id = self.store.start_run()
        queue = asyncio.Queue(maxsize=self.config.concurrency * 2)
        seen = set()

        async def worker():
            while True:
                doc = await queue.get()
                try:
                    if doc is None:
                        return
                    if self.auth_failed and doc.source == "ev":
                        self.summary.errors += 1
                        continue
                    await self.one(doc)
                except SessionExpired as exc:
                    self.auth_failed = True
                    self.summary.errors += 1
                    self.report(f"Sesión: {exc}")
                except Exception as exc:
                    self.summary.errors += 1
                    self.report(f"Error · {doc.course} / {doc.parts[-1]}: {exc}")
                finally:
                    queue.task_done()

        workers = [asyncio.create_task(worker()) for _ in range(self.config.concurrency)]
        completed = False
        try:
            try:
                async for doc in documents:
                    if excluded(doc, self.store):
                        continue
                    if doc.key not in seen:
                        seen.add(doc.key)
                        await queue.put(doc)
            except Exception as exc:
                self.summary.errors += 1
                self.report(f"Descubrimiento incompleto: {exc}")
            for _ in workers:
                await queue.put(None)
            await asyncio.gather(*workers)
            completed = True
        finally:
            for task in workers:
                if not task.done():
                    task.cancel()
            await asyncio.gather(*workers, return_exceptions=True)
            self.store.finish_run(run_id, {**asdict(self.summary), "completed": completed})
        return self.summary

    async def one(self, doc):
        target = target_path(self.config.dest, doc)
        state = self.store.file(doc.key)
        existing = Path(state["path"]) if state else target
        valid = bool(
            state
            and existing.is_file()
            and await asyncio.to_thread(digest, existing) == state["hash"]
        )
        conflict = target.exists() and not (state and existing == target and valid)
        if conflict:
            self.summary.conflicts += 1
            self.report(
                f"Conservado · {doc.parts[-1]}: archivo local modificado o ajeno; mueve tu copia para actualizarlo."
            )
            return
        target.parent.mkdir(parents=True, exist_ok=True)
        if state and valid and doc.revision is not None and state["revision"] == doc.revision:
            if existing != target:
                fd, tmp = tempfile.mkstemp(prefix=".ussync-", suffix=".part", dir=target.parent)
                os.close(fd)
                try:
                    await asyncio.to_thread(shutil.copyfile, existing, tmp)
                    os.replace(tmp, target)
                finally:
                    Path(tmp).unlink(missing_ok=True)
                self.store.record(doc, target, state["hash"], state["etag"], state["modified"])
                self.summary.reused += 1
            else:
                self.summary.unchanged += 1
            return

        conditional = {}
        if valid and existing == target:
            if state["etag"]:
                conditional["If-None-Match"] = state["etag"]
            elif state["modified"]:
                conditional["If-Modified-Since"] = state["modified"]
        fd, temporary = tempfile.mkstemp(prefix=".ussync-", suffix=".part", dir=target.parent)
        os.close(fd)
        temporary = Path(temporary)
        try:
            headers, unchanged = await self.transports[doc.source].download(
                doc, temporary, conditional
            )
            if unchanged:
                if not valid or existing != target:
                    raise RemoteError("La fuente devolvió 304 sin una copia local verificada.")
                self.store.record(
                    doc,
                    target,
                    state["hash"],
                    headers.get("etag", state["etag"]),
                    headers.get("last-modified", state["modified"]),
                )
                self.summary.unchanged += 1
                return
            validate(temporary, doc, headers)
            sha = await asyncio.to_thread(digest, temporary)
            self.summary.bytes += temporary.stat().st_size
            if valid and existing == target and sha == state["hash"]:
                self.store.record(
                    doc, target, sha, headers.get("etag"), headers.get("last-modified")
                )
                self.summary.unchanged += 1
                return
            # Recheck just before publishing: user edits during the request survive.
            if target.exists():
                if not state or await asyncio.to_thread(digest, target) != state["hash"]:
                    self.summary.conflicts += 1
                    self.report(f"Conservado · {doc.parts[-1]}: cambió durante la descarga.")
                    return
                history = target.parent / ".versiones"
                history.mkdir(exist_ok=True)
                backup = history / f"{target.stem}.{state['hash'][:12]}{target.suffix}"
                if not backup.exists():
                    await asyncio.to_thread(shutil.copyfile, target, backup)
            os.replace(temporary, target)
            self.store.record(doc, target, sha, headers.get("etag"), headers.get("last-modified"))
            if state:
                self.summary.updated += 1
            else:
                self.summary.downloaded += 1
            self.report(f"Guardado · {doc.course} / {doc.parts[-1]}")
        finally:
            temporary.unlink(missing_ok=True)


def excluded(doc, store):
    if doc.key in store.get("excluded_keys", []):
        return True
    for rule in store.get("excluded_folders", []):
        prefix = tuple(rule["parts"])
        if rule["course"] == doc.course and tuple(doc.parts[: len(prefix)]) == prefix:
            return True
    return False
