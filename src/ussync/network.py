from __future__ import annotations

import asyncio
from datetime import datetime, timezone
from email.utils import parsedate_to_datetime
from pathlib import Path

import httpx

from .errors import AccessDenied, RemoteError, SessionExpired


def retry_delay(value: str | None, attempt: int) -> float:
    if value:
        try:
            return max(0, min(120, float(value)))
        except ValueError:
            try:
                return max(
                    0,
                    min(
                        120,
                        (parsedate_to_datetime(value) - datetime.now(timezone.utc)).total_seconds(),
                    ),
                )
            except (ValueError, TypeError):
                pass
    return min(2**attempt, 8)


def check_status(status: int):
    if status == 401:
        raise SessionExpired(
            "La sesión ha caducado. Ejecuta «ussync login» y repite la sincronización."
        )
    if status == 403:
        raise AccessDenied("La fuente ha denegado el acceso a este recurso (403).")
    if status >= 400:
        raise RemoteError(f"La fuente respondió HTTP {status}.")


class HTTP:
    def __init__(self, client: httpx.AsyncClient | None = None):
        self.client = client or httpx.AsyncClient(
            follow_redirects=True,
            timeout=httpx.Timeout(60, connect=20),
            headers={"User-Agent": "USSync/0.1 (personal university document sync)"},
        )

    async def close(self):
        await self.client.aclose()

    async def request(self, method, url, **kwargs):
        for attempt in range(4):
            try:
                response = await self.client.request(method, url, **kwargs)
            except httpx.TransportError:
                if attempt == 3:
                    raise RemoteError(
                        "No se pudo conectar con la fuente tras varios intentos."
                    ) from None
                await asyncio.sleep(retry_delay(None, attempt))
                continue
            if response.status_code in {429, 500, 502, 503, 504} and attempt < 3:
                await asyncio.sleep(retry_delay(response.headers.get("Retry-After"), attempt))
                continue
            return response
        raise AssertionError("unreachable")

    async def download(self, doc, temporary: Path, headers: dict):
        """Stream into a disposable file; retries restart only this transfer."""
        for attempt in range(4):
            try:
                async with self.client.stream(
                    doc.method, doc.url, data=doc.form or None, headers=headers
                ) as response:
                    if response.status_code in {429, 500, 502, 503, 504} and attempt < 3:
                        delay = retry_delay(response.headers.get("Retry-After"), attempt)
                    else:
                        check_status(response.status_code)
                        if response.status_code == 304:
                            return dict(response.headers), True
                        with temporary.open("wb") as stream:
                            async for chunk in response.aiter_bytes(chunk_size=128 * 1024):
                                stream.write(chunk)
                        return dict(response.headers), False
            except httpx.TransportError:
                if attempt == 3:
                    raise RemoteError("Descarga interrumpida tras varios intentos.") from None
                delay = retry_delay(None, attempt)
            await asyncio.sleep(delay)
        raise AssertionError("unreachable")
