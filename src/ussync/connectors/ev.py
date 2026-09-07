from __future__ import annotations

import asyncio
import json
import time
from contextlib import asynccontextmanager
from http.cookiejar import Cookie
from urllib.parse import quote, urljoin, urlparse

from playwright.async_api import Error as BrowserError
from playwright.async_api import async_playwright

from ..errors import AccessDenied, RemoteError, SessionExpired
from ..models import Document
from ..network import check_status, retry_delay
from ..storage import private_json, slug


def file_size(attachment):
    for field in ("size", "fileSize", "sizeBytes"):
        value = attachment.get(field)
        if isinstance(value, (int, float)) and value >= 0:
            return int(value)
        if isinstance(value, str) and value.isdigit():
            return int(value)
    return None


@asynccontextmanager
async def browser(config, interactive=False):
    state_file = config.state / "ev-session.json"
    storage = None
    if state_file.exists():
        try:
            storage = json.loads(state_file.read_text())
        except (ValueError, OSError) as exc:
            raise RemoteError(
                "No se puede leer ev-session.json; retíralo y ejecuta «ussync login»."
            ) from exc
    async with async_playwright() as pw:
        try:
            instance = await pw.chromium.launch(headless=not interactive)
        except BrowserError as exc:
            raise RemoteError(
                "No se pudo abrir Chromium. Ejecuta «python -m playwright install chromium». Para login necesitas una sesión gráfica."
            ) from exc
        context = None
        try:
            context = await instance.new_context(storage_state=storage, accept_downloads=True)
            yield context
        finally:
            try:
                if context:
                    private_json(state_file, await context.storage_state())
                    await context.close()
            finally:
                await instance.close()


class EV:
    def __init__(self, context, base_url):
        self.context, self.base_url = context, base_url
        self.prefix = None

    def same_origin(self, url):
        full = urljoin(self.base_url + "/", url)
        if (
            urlparse(full).netloc != urlparse(self.base_url).netloc
            or urlparse(full).scheme != "https"
        ):
            raise RemoteError("La API devolvió un enlace fuera del origen de Enseñanza Virtual.")
        return full

    async def response(self, url):
        for attempt in range(4):
            try:
                response = await self.context.request.get(self.same_origin(url), timeout=30_000)
            except BrowserError:
                if attempt == 3:
                    raise RemoteError(
                        "No se pudo conectar con Enseñanza Virtual; no se ha confirmado una caducidad de sesión."
                    ) from None
                await asyncio.sleep(retry_delay(None, attempt))
                continue
            try:
                status, headers = response.status, response.headers
                if status in {429, 500, 502, 503, 504} and attempt < 3:
                    delay = retry_delay(headers.get("retry-after"), attempt)
                else:
                    try:
                        data = await response.json()
                    except (ValueError, BrowserError):
                        data = None
                    return status, data
            finally:
                await response.dispose()
            await asyncio.sleep(delay)
        raise AssertionError("unreachable")

    async def user(self):
        statuses = []
        for prefix in ("/learn/api/public/v1", "/learn/api/v1"):
            status, data = await self.response(prefix + "/users/me")
            statuses.append(status)
            if status == 200 and isinstance(data, dict) and data.get("id"):
                self.prefix = prefix
                return data
        if any(s >= 500 or s == 429 for s in statuses):
            raise RemoteError("Enseñanza Virtual no está disponible temporalmente.")
        if all(s == 404 for s in statuses):
            raise RemoteError("No se encontró una API de perfil compatible en esta instalación.")
        return None

    async def authenticate(self, interactive=False):
        user = await self.user()
        if user:
            return user
        page = await self.context.new_page()
        deadline = time.monotonic() + (300 if interactive else 30)
        try:
            try:
                await page.goto(
                    self.base_url + "/ultra", wait_until="domcontentloaded", timeout=30_000
                )
            except BrowserError:
                if not interactive:
                    raise RemoteError(
                        "No se pudo cargar Enseñanza Virtual para renovar la sesión."
                    ) from None
            while time.monotonic() < deadline:
                await asyncio.sleep(2)
                # An /ultra URL alone is not evidence of completed authentication.
                if urlparse(page.url).netloc == urlparse(self.base_url).netloc:
                    user = await self.user()
                    if user:
                        return user
            raise SessionExpired(
                "No hay una sesión válida. Ejecuta «ussync login» y completa el acceso en el navegador."
            )
        finally:
            await page.close()

    async def get(self, suffix):
        prefixes = [self.prefix or "/learn/api/public/v1"]
        alternate = "/learn/api/v1" if "public" in prefixes[0] else "/learn/api/public/v1"
        prefixes.append(alternate)
        for index, prefix in enumerate(prefixes):
            url = suffix if suffix.startswith(("http", "/learn/")) else prefix + suffix
            status, data = await self.response(url)
            if (
                status in {401, 403, 404}
                and index == 0
                and not suffix.startswith(("http", "/learn/"))
            ):
                continue
            check_status(status)
            if data is None:
                raise RemoteError(
                    "Enseñanza Virtual devolvió HTML en vez de JSON; comprueba el login."
                )
            self.prefix = prefix
            return data, prefix
        raise RemoteError("No se pudo consultar la API.")

    async def paged(self, suffix):
        seen = set()
        while suffix:
            if suffix in seen:
                raise RemoteError("La API repitió una página; se detuvo para evitar un bucle.")
            seen.add(suffix)
            data, _ = await self.get(suffix)
            if isinstance(data, list):
                for item in data:
                    yield item
                break
            if not isinstance(data, dict) or not isinstance(data.get("results"), list):
                raise RemoteError("Formato de listado de Enseñanza Virtual no compatible.")
            for item in data["results"]:
                yield item
            suffix = (data.get("paging") or {}).get("nextPage")

    async def courses(self, user_id):
        result = []
        async for membership in self.paged(
            f"/users/{quote(user_id, safe='')}/courses?expand=course"
        ):
            course = membership.get("course") or {}
            cid = course.get("id") or membership.get("courseId")
            if cid:
                if not course.get("name"):
                    course, _ = await self.get(f"/courses/{quote(cid, safe='')}")
                result.append(
                    {
                        "id": cid,
                        "name": course.get("name") or cid,
                        "courseId": course.get("courseId", ""),
                    }
                )
        return sorted(result, key=lambda c: c["name"].casefold())

    async def documents(self, courses, report):
        for course in courses:
            report(f"Explorando · {course['name']}")
            cid = quote(course["id"], safe="")
            folder = course.get("folder") or f"{course['name']} [{course['id']}]"
            pending = [(f"/courses/{cid}/contents", ("Materiales",))]
            visited = set()
            while pending:
                suffix, parents = pending.pop()
                try:
                    async for item in self.paged(suffix):
                        iid = str(item.get("id") or "")
                        if not iid or iid in visited:
                            continue
                        visited.add(iid)
                        title = str(item.get("title") or "Contenido")
                        encoded = quote(iid, safe="")
                        handler = (item.get("contentHandler") or {}).get("id", "")
                        is_folder = handler in {"resource/x-bb-folder", "resource/x-bb-lesson"}
                        if item.get("hasChildren"):
                            branch = slug(title) + " [" + slug(iid) + "]"
                            pending.append(
                                (f"/courses/{cid}/contents/{encoded}/children", (*parents, branch))
                            )
                        if is_folder or handler in {
                            "resource/x-bb-externallink",
                            "resource/x-bb-blti-link",
                        }:
                            continue
                        try:
                            async for att in self.paged(
                                f"/courses/{cid}/contents/{encoded}/attachments"
                            ):
                                aid = str(att.get("id") or "")
                                if not aid:
                                    continue
                                name = str(att.get("fileName") or att.get("name") or aid)
                                download = (
                                    (self.prefix or "/learn/api/v1")
                                    + f"/courses/{cid}/contents/{encoded}/attachments/{quote(aid, safe='')}/download"
                                )
                                yield Document(
                                    f"ev:{course['id']}:{iid}:{aid}",
                                    "ev",
                                    folder,
                                    (*parents, name),
                                    self.same_origin(download),
                                    str(att.get("modified") or item.get("modified"))
                                    if att.get("modified") or item.get("modified")
                                    else None,
                                    pdf=name.lower().endswith(".pdf"),
                                    size=file_size(att),
                                )
                        except AccessDenied:
                            report(f"Sin acceso a adjuntos · {title}")
                            raise
                        except RemoteError as exc:
                            # A 404 on attachments is normal for non-file content.
                            if "HTTP 404" not in str(exc):
                                raise
                except SessionExpired:
                    raise
                except (RemoteError, AccessDenied) as exc:
                    # Caller counts discovery errors without blocking other courses.
                    report(f"ERROR · {course['name']} / {suffix}: {exc}")

    async def transfer_cookies(self, http):
        http.client.headers["User-Agent"] = (
            await self.context.pages[0].evaluate("navigator.userAgent")
            if self.context.pages
            else "Mozilla/5.0 USSync/0.1"
        )
        for c in await self.context.cookies():
            expires = c.get("expires", -1)
            http.client.cookies.jar.set_cookie(
                Cookie(
                    version=0,
                    name=c["name"],
                    value=c["value"],
                    port=None,
                    port_specified=False,
                    domain=c["domain"],
                    domain_specified=c["domain"].startswith("."),
                    domain_initial_dot=c["domain"].startswith("."),
                    path=c["path"],
                    path_specified=True,
                    secure=c.get("secure", False),
                    expires=int(expires) if expires > 0 else None,
                    discard=expires <= 0,
                    comment=None,
                    comment_url=None,
                    rest={},
                    rfc2109=False,
                )
            )
