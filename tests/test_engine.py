import asyncio
from dataclasses import replace

import httpx

from ussync.demo import pdf
from ussync.engine import Engine, target_path
from ussync.models import Document
from ussync.network import HTTP


def document(key="one", revision="v1"):
    return Document(
        key,
        "test",
        "Curso",
        ("Materiales", "notes.pdf"),
        "https://test.invalid/file",
        revision,
        pdf=True,
    )


async def generate(*docs):
    for doc in docs:
        yield doc


async def test_second_run_and_missing_local_copy(config, store):
    calls = []

    def handler(request):
        calls.append(request)
        return httpx.Response(200, content=pdf("Test"))

    http = HTTP(httpx.AsyncClient(transport=httpx.MockTransport(handler)))
    doc = document()
    for _ in range(2):
        result = await Engine(config, store, {"test": http}).run(generate(doc))
    assert len(calls) == 1 and result.unchanged == 1
    target_path(config.dest, doc).unlink()
    result = await Engine(config, store, {"test": http}).run(generate(doc))
    assert len(calls) == 2 and result.errors == 0
    await http.close()


async def test_user_edit_survives_remote_update(config, store):
    http = HTTP(
        httpx.AsyncClient(
            transport=httpx.MockTransport(lambda req: httpx.Response(200, content=pdf("Old")))
        )
    )
    doc = document()
    await Engine(config, store, {"test": http}).run(generate(doc))
    path = target_path(config.dest, doc)
    path.write_bytes(b"my annotations")
    result = await Engine(config, store, {"test": http}).run(generate(replace(doc, revision="v2")))
    assert result.conflicts == 1 and path.read_bytes() == b"my annotations"
    await http.close()


async def test_html_does_not_replace_existing_pdf(config, store):
    content = pdf("Old")
    http = HTTP(
        httpx.AsyncClient(
            transport=httpx.MockTransport(lambda req: httpx.Response(200, content=content))
        )
    )
    doc = document()
    await Engine(config, store, {"test": http}).run(generate(doc))
    old = target_path(config.dest, doc).read_bytes()
    content = b"<html>login required</html>"
    result = await Engine(config, store, {"test": http}).run(generate(replace(doc, revision="v2")))
    assert result.errors == 1 and target_path(config.dest, doc).read_bytes() == old
    assert not list(config.dest.rglob("*.part"))
    assert store.file(doc.key)["revision"] == "v1"
    await http.close()


async def test_relocation_reuses_verified_file(config, store):
    calls = []

    def handler(req):
        calls.append(req)
        return httpx.Response(200, content=pdf("Test"))

    http = HTTP(httpx.AsyncClient(transport=httpx.MockTransport(handler)))
    doc = document()
    await Engine(config, store, {"test": http}).run(generate(doc))
    moved = replace(config, dest=config.dest.parent / "other")
    result = await Engine(moved, store, {"test": http}).run(generate(doc))
    assert result.reused == 1 and len(calls) == 1
    assert target_path(moved.dest, doc).is_file()
    await http.close()


async def test_concurrency_bounded_and_duplicate_filenames(config, store):
    active = peak = 0

    async def handler(req):
        nonlocal active, peak
        active += 1
        peak = max(peak, active)
        await asyncio.sleep(0.02)
        active -= 1
        return httpx.Response(200, content=pdf("Test"))

    http = HTTP(httpx.AsyncClient(transport=httpx.MockTransport(handler)))
    result = await Engine(config, store, {"test": http}).run(
        generate(*(document(str(i)) for i in range(6)))
    )
    assert peak == config.concurrency and result.downloaded == 6
    assert len(list(config.dest.rglob("*.pdf"))) == 6
    await http.close()


async def test_folder_exclusion_applies_to_future_files(config, store):
    store.set("excluded_folders", [{"course": "Curso", "parts": ["Materiales"]}])
    result = await Engine(config, store, {}).run(generate(document("new-file")))
    assert result.downloaded == result.errors == 0


async def test_changed_remote_preserves_previous_version(config, store):
    content = pdf("Old")
    http = HTTP(
        httpx.AsyncClient(
            transport=httpx.MockTransport(lambda req: httpx.Response(200, content=content))
        )
    )
    doc = document()
    await Engine(config, store, {"test": http}).run(generate(doc))
    old = content
    content = pdf("New")
    result = await Engine(config, store, {"test": http}).run(generate(replace(doc, revision="v2")))
    assert result.updated == 1
    backups = list(config.dest.rglob(".versiones/*.pdf"))
    assert len(backups) == 1 and backups[0].read_bytes() == old
    await http.close()


async def test_conditional_304_requires_valid_copy(config, store):
    def handler(req):
        if req.headers.get("If-None-Match") == '"version"':
            return httpx.Response(304)
        return httpx.Response(200, content=pdf("Test"), headers={"ETag": '"version"'})

    http = HTTP(httpx.AsyncClient(transport=httpx.MockTransport(handler)))
    doc = document(revision=None)
    await Engine(config, store, {"test": http}).run(generate(doc))
    result = await Engine(config, store, {"test": http}).run(generate(doc))
    assert result.unchanged == 1 and result.bytes == 0
    await http.close()
