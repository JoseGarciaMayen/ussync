from pathlib import Path

import pytest

from ussync.config import Config
from ussync.errors import USSyncError


def test_env_relative_paths_and_priority(tmp_path, monkeypatch):
    env = tmp_path / ".env"
    env.write_text(
        "USSYNC_DEST='notes with spaces'\nUSSYNC_STATE_DIR=private\nUSSYNC_CONCURRENCY=2\n"
    )
    monkeypatch.chdir("/tmp")
    config = Config.load(env)
    assert config.dest == tmp_path / "notes with spaces"
    monkeypatch.setenv("USSYNC_DEST", str(tmp_path / "shell"))
    assert Config.load(env).dest == tmp_path / "shell"
    assert Config.load(env, dest=tmp_path / "cli").dest == tmp_path / "cli"


def test_state_cannot_be_shared(tmp_path):
    with pytest.raises(USSyncError, match="fuera"):
        Config.load(dest=tmp_path, state=tmp_path / "session")


def test_invalid_concurrency(tmp_path):
    env = tmp_path / ".env"
    env.write_text("USSYNC_CONCURRENCY=nope")
    with pytest.raises(USSyncError, match="entero"):
        Config.load(env)


def test_explicit_missing_env_is_error(tmp_path):
    with pytest.raises(USSyncError, match="No existe"):
        Config.load(Path(tmp_path / "missing"))
