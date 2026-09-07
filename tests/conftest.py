import pytest

from ussync.config import Config
from ussync.storage import Store


@pytest.fixture
def config(tmp_path):
    return Config(tmp_path / "library", tmp_path / "state", concurrency=2)


@pytest.fixture
def store(config):
    result = Store(config.state)
    yield result
    result.close()
