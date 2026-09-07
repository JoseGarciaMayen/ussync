from __future__ import annotations

import hashlib
import json
import os
import re
import sqlite3
import tempfile
from datetime import datetime, timezone
from pathlib import Path


def utcnow():
    return datetime.now(timezone.utc).isoformat(timespec="seconds")


def private_json(path: Path, value):
    path.parent.mkdir(parents=True, exist_ok=True, mode=0o700)
    fd, tmp = tempfile.mkstemp(prefix=".state-", dir=path.parent)
    try:
        with os.fdopen(fd, "w", encoding="utf-8") as stream:
            json.dump(value, stream, ensure_ascii=False, indent=2)
        os.replace(tmp, path)
    finally:
        Path(tmp).unlink(missing_ok=True)


def slug(value: str) -> str:
    name = re.sub(r'[<>:"/\\|?*\x00-\x1f]', "-", str(value))
    name = re.sub(r"\s+", " ", name).strip(" .")[:100].rstrip(" .") or "Sin título"
    if name.split(".")[0].upper() in {
        "CON",
        "PRN",
        "AUX",
        "NUL",
        *(f"COM{i}" for i in range(1, 10)),
        *(f"LPT{i}" for i in range(1, 10)),
    }:
        name = "_" + name
    return name


def digest(path: Path) -> str:
    with path.open("rb") as stream:
        return hashlib.file_digest(stream, "sha256").hexdigest()


class Store:
    def __init__(self, root: Path):
        root.mkdir(parents=True, exist_ok=True, mode=0o700)
        self.db = sqlite3.connect(root / "catalog.sqlite3")
        self.db.row_factory = sqlite3.Row
        self.db.executescript("""
          CREATE TABLE IF NOT EXISTS settings (key TEXT PRIMARY KEY, value TEXT NOT NULL);
          CREATE TABLE IF NOT EXISTS files (
            key TEXT PRIMARY KEY, revision TEXT, path TEXT NOT NULL,
            hash TEXT NOT NULL, etag TEXT, modified TEXT, synced TEXT NOT NULL
          );
          CREATE TABLE IF NOT EXISTS runs (
            id INTEGER PRIMARY KEY, started TEXT NOT NULL, ended TEXT, summary TEXT
          );
        """)

    def close(self):
        self.db.close()

    def get(self, key, default=None):
        row = self.db.execute("SELECT value FROM settings WHERE key=?", (key,)).fetchone()
        return json.loads(row[0]) if row else default

    def set(self, key, value):
        with self.db:
            self.db.execute(
                "INSERT OR REPLACE INTO settings VALUES (?,?)", (key, json.dumps(value))
            )

    def file(self, key):
        row = self.db.execute("SELECT * FROM files WHERE key=?", (key,)).fetchone()
        return dict(row) if row else None

    def record(self, doc, path, sha, etag=None, modified=None):
        with self.db:
            self.db.execute(
                "INSERT OR REPLACE INTO files VALUES (?,?,?,?,?,?,?)",
                (doc.key, doc.revision, str(path), sha, etag, modified, utcnow()),
            )

    def start_run(self):
        with self.db:
            return self.db.execute("INSERT INTO runs(started) VALUES (?)", (utcnow(),)).lastrowid

    def finish_run(self, run_id, summary):
        with self.db:
            self.db.execute(
                "UPDATE runs SET ended=?, summary=? WHERE id=?",
                (utcnow(), json.dumps(summary), run_id),
            )

    def last_run(self):
        row = self.db.execute("SELECT * FROM runs ORDER BY id DESC LIMIT 1").fetchone()
        return dict(row) if row else None
