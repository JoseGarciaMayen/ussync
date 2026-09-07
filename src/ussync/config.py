from __future__ import annotations

import os
from dataclasses import dataclass
from pathlib import Path
from urllib.parse import urlparse

from dotenv import dotenv_values

from .errors import USSyncError


def default_env() -> Path:
    # Editable checkout; a wheel installation instead uses the user's config dir.
    checkout = Path(__file__).resolve().parents[2]
    if (checkout / "pyproject.toml").exists():
        return checkout / ".env"
    return Path.home() / ".config" / "ussync" / ".env"


@dataclass(frozen=True)
class Config:
    dest: Path
    state: Path
    concurrency: int = 4
    ev_url: str = "https://ev.us.es"
    degree: str = "247"
    centers: tuple[str, ...] = ("17", "3")

    @classmethod
    def load(cls, env_file=None, dest=None, state=None, concurrency=None):
        env_path = Path(env_file).expanduser().resolve() if env_file else default_env()
        if env_file and not env_path.is_file():
            raise USSyncError(f"No existe el archivo de configuración: {env_path}")
        values = {**dotenv_values(env_path), **os.environ}

        def path(value):
            result = Path(value).expanduser()
            return (
                (env_path.parent / result).resolve()
                if not result.is_absolute()
                else result.resolve()
            )

        try:
            limit = int(concurrency or values.get("USSYNC_CONCURRENCY") or 4)
        except ValueError as exc:
            raise USSyncError("USSYNC_CONCURRENCY debe ser un entero entre 1 y 8.") from exc
        if not 1 <= limit <= 8:
            raise USSyncError("USSYNC_CONCURRENCY debe estar entre 1 y 8.")
        target = path(dest or values.get("USSYNC_DEST") or "~/Documents/USSync")
        private = path(state or values.get("USSYNC_STATE_DIR") or "~/.local/share/ussync")
        if private == target or private.is_relative_to(target):
            raise USSyncError(
                "USSYNC_STATE_DIR debe estar fuera de USSYNC_DEST (contiene la sesión)."
            )
        url = (values.get("USSYNC_EV_URL") or "https://ev.us.es").rstrip("/")
        parsed = urlparse(url)
        if parsed.scheme != "https" or not parsed.netloc or parsed.path or parsed.query:
            raise USSyncError(
                "USSYNC_EV_URL debe ser un origen HTTPS, por ejemplo https://ev.us.es"
            )
        centers = tuple(
            x.strip()
            for x in (values.get("USSYNC_SEVIUS_CENTERS") or "17,3").split(",")
            if x.strip()
        )
        return cls(
            target, private, limit, url, values.get("USSYNC_SEVIUS_DEGREE") or "247", centers
        )
