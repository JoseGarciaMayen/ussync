from dataclasses import dataclass, field


@dataclass(frozen=True)
class Subject:
    code: str
    name: str
    center: str


@dataclass(frozen=True)
class Document:
    key: str
    source: str
    course: str
    parts: tuple[str, ...]
    url: str
    revision: str | None = None
    method: str = "GET"
    form: dict[str, str] = field(default_factory=dict)
    pdf: bool = False
    size: int | None = None


@dataclass
class Summary:
    downloaded: int = 0
    updated: int = 0
    unchanged: int = 0
    reused: int = 0
    conflicts: int = 0
    errors: int = 0
    bytes: int = 0
