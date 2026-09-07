import httpx
import pytest

from ussync.connectors.sevius import Sevius, options, teaching_documents
from ussync.errors import RemoteError
from ussync.models import Subject
from ussync.network import HTTP

PAGE = """
<select id="asignatura"><option value="-1">Seleccione</option><option value="999">Asignatura ficticia (999)</option></select>
<table><caption>Versión 3</caption><form><input name="programa" value="999/3"></form></table>
<table><caption>Versión 2</caption><form><input name="programa" value="999/2"></form>
<tr><th>Proyecto del grupo A<form><input name="proyecto" value="999/2026-27/42/A"></form></th></tr>
<tr><th>Proyecto del grupo B<form><input name="proyecto" value="999/2026-27/43/B"></form></th></tr></table>
<table><caption>Versión 1</caption><form><input name="programa" value="999/1"></form>
<tr><th>Proyecto del grupo A<form><input name="proyecto" value="999/2025-26/42/A"></form></th></tr></table>
"""


def test_program_matches_selected_year_not_latest_version():
    documents = teaching_documents(PAGE)
    project = next(d for d in documents if d.value == "999/2026-27/42/A")
    assert project.program == "999/2"
    assert project.year == "2026-27"
    assert "grupo A" in project.label


def test_unknown_page_is_not_empty_catalog():
    with pytest.raises(RemoteError):
        options("<html>Server error</html>", "asignatura")


async def test_public_selection_adds_associated_program():
    http = HTTP(
        httpx.AsyncClient(transport=httpx.MockTransport(lambda req: httpx.Response(200, text=PAGE)))
    )
    sevius = Sevius(http)
    subject = Subject("999", "Asignatura ficticia", "17")
    project = next(d for d in teaching_documents(PAGE) if d.value == "999/2026-27/42/A")
    docs = [d async for d in sevius.selected_documents([sevius.selection(subject, [project])])]
    assert len(docs) == 2
    assert {tuple(d.form.values())[0] for d in docs} == {project.value, "999/2"}
    assert all(d.method == "POST" and d.pdf for d in docs)
    await http.close()


async def test_catalog_merges_both_centers_without_duplicate_subjects():
    calls = []

    def handler(request):
        calls.append(request.content)
        return httpx.Response(200, text=PAGE)

    http = HTTP(httpx.AsyncClient(transport=httpx.MockTransport(handler)))
    subjects = await Sevius(http).subjects()
    assert len(calls) == 2 and len(subjects) == 1
    assert subjects[0].name == "Asignatura ficticia"
    await http.close()
