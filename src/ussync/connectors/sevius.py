from __future__ import annotations

import re
from dataclasses import asdict, dataclass

from bs4 import BeautifulSoup

from ..errors import RemoteError
from ..models import Document, Subject
from ..network import check_status

LIST_URL = "https://sevius4.us.es/index.php?PyP=LISTA"


@dataclass(frozen=True)
class TeachingDocument:
    kind: str
    value: str
    label: str
    year: str = ""
    program: str = ""


def options(html: str, select: str) -> list[tuple[str, str]]:
    soup = BeautifulSoup(html, "html.parser")
    element = soup.find("select", id=select)
    if element is None:
        raise RemoteError(
            f"SEVIUS no muestra el selector {select}; puede haber cambiado la página."
        )
    return [
        (str(o.get("value")), o.get_text(" ", strip=True))
        for o in element.find_all("option")
        if o.get("value") not in (None, "", "-1")
    ]


def teaching_documents(html: str) -> list[TeachingDocument]:
    soup = BeautifulSoup(html, "html.parser")
    documents = {}
    for table in soup.find_all("table"):
        program_input = table.find("input", attrs={"name": "programa"})
        if program_input is None:
            continue
        program = str(program_input.get("value", ""))
        if not program:
            continue
        caption = table.find("caption")
        version = caption.get_text(" ", strip=True) if caption else program.split("/")[-1]
        documents[("programa", program)] = TeachingDocument(
            "programa", program, f"Programa · {version}"
        )
        for entry in table.find_all("input", attrs={"name": "proyecto"}):
            value = str(entry.get("value", ""))
            parts = value.split("/")
            if len(parts) != 4 or not re.fullmatch(r"\d{4}-\d{2}", parts[1]):
                raise RemoteError("SEVIUS ha cambiado el identificador de los proyectos docentes.")
            cell = entry.find_parent("th") or entry.find_parent("td")
            label = cell.get_text(" ", strip=True) if cell else f"Proyecto del grupo {parts[-1]}"
            documents[("proyecto", value)] = TeachingDocument(
                "proyecto", value, f"{label} · {parts[1]} · {version}", parts[1], program
            )
    return list(documents.values())


class Sevius:
    def __init__(self, http, degree="247", centers=("17", "3")):
        self.http, self.degree, self.centers = http, degree, centers

    async def page(self, **form):
        response = await self.http.request("POST", LIST_URL, data=form)
        check_status(response.status_code)
        return response.text

    async def subjects(self):
        result = {}
        for center in self.centers:
            html = await self.page(codcentro=center, titulacion=self.degree)
            for code, name in options(html, "asignatura"):
                result.setdefault(
                    code,
                    Subject(code, re.sub(r"\s*\(" + re.escape(code) + r"\)$", "", name), center),
                )
        return sorted(result.values(), key=lambda s: s.name.casefold())

    async def documents(self, subject: Subject):
        html = await self.page(
            codcentro=subject.center, titulacion=self.degree, asignatura=subject.code
        )
        return teaching_documents(html)

    @staticmethod
    def selection(subject, selected):
        return {"subject": asdict(subject), "documents": [asdict(d) for d in selected]}

    async def selected_documents(self, selections):
        for selection in selections:
            subject = Subject(**selection["subject"])
            # Re-read the published listing: selected groups may move to a new program version.
            published = await self.documents(subject)
            lookup = {(d.kind, d.value): d for d in published}
            wanted = {}
            for saved in selection["documents"]:
                key = (saved["kind"], saved["value"])
                doc = lookup.get(key)
                if doc is None:
                    raise RemoteError(
                        f"Ya no está publicado {saved['label']} de {subject.name}; revisa «ussync select»."
                    )
                wanted[key] = doc
                if doc.program:
                    wanted[("programa", doc.program)] = lookup[("programa", doc.program)]
            for item in wanted.values():
                yield self.document(subject, item, selection.get("folder"))

    @staticmethod
    def document(subject, item, folder=None):
        name = (
            f"Programa v{item.value.split('/')[-1]}.pdf"
            if item.kind == "programa"
            else f"{item.label}.pdf"
        )
        return Document(
            key=f"sevius:{item.kind}:{item.value}",
            source="sevius",
            course=folder or f"{subject.name} [{subject.code}]",
            parts=("Documentación", name),
            url=LIST_URL,
            method="POST",
            form={item.kind: item.value},
            pdf=True,
            # The same published identifier can be edited: compare payload hashes.
        )
