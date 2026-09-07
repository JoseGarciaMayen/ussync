import asyncio

import httpx
import pytest

from ussync.web import create_app


@pytest.fixture
async def panel(config):
    app = create_app(config, demo_mode=True)
    async with app.router.lifespan_context(app):
        async with httpx.AsyncClient(
            transport=httpx.ASGITransport(app),
            base_url="http://testserver",
            headers={"X-USSync-Token": app.state.token},
        ) as client:
            yield client, app


async def wait_job(client):
    for _ in range(200):
        state = (await client.get("/api/state")).json()
        if not state["job"]["running"]:
            assert not state["job"]["error"], state["job"]
            return state
        await asyncio.sleep(0.01)
    raise AssertionError("Job never completed")


async def test_panel_demo_full_flow(panel, config):
    client, app = panel
    assert (await client.post("/api/login", json={})).status_code == 200
    state = await wait_job(client)
    courses = [{"id": c["id"]} for c in state["courses"]]
    assert (await client.post("/api/courses", json={"courses": courses})).status_code == 200
    await client.post("/api/scan", json={})
    state = await wait_job(client)
    assert len(state["documents"]) == 4
    keys = [state["documents"][0]["key"]]
    await client.post("/api/selection", json={"keys": keys})
    await client.post("/api/download", json={"keys": keys})
    state = await wait_job(client)
    assert state["job"]["result"]["downloaded"] == 1
    assert len(list(config.dest.rglob("*.pdf"))) == 1
    entries = (await client.get("/api/library", params={"q": "pdf"})).json()["entries"]
    response = await client.get("/api/file", params={"path": entries[0]["path"]})
    assert response.content.startswith(b"%PDF-")


async def test_rejects_cross_origin_and_missing_token(panel):
    client, app = panel
    assert (
        await client.post("/api/login", json={}, headers={"Origin": "https://evil.invalid"})
    ).status_code == 403
    assert (await client.get("/api/state", headers={"X-USSync-Token": "wrong"})).status_code == 403
    assert (await client.get("/api/state", headers={"Host": "evil.invalid"})).status_code == 403


async def test_library_path_traversal_blocked(panel):
    client, app = panel
    assert (await client.get("/api/file", params={"path": "../../etc/passwd"})).status_code == 400


async def test_user_cannot_inject_arbitrary_download_url(panel):
    client, app = panel
    assert (
        await client.post("/api/download", json={"keys": ["https://evil.invalid"]})
    ).status_code == 400


async def test_config_saved_to_env(config, tmp_path):
    env = tmp_path / ".env"
    app = create_app(config, env_file=env)
    async with app.router.lifespan_context(app):
        async with httpx.AsyncClient(
            transport=httpx.ASGITransport(app),
            base_url="http://testserver",
            headers={"X-USSync-Token": app.state.token},
        ) as client:
            destination = tmp_path / "my notes"
            response = await client.post(
                "/api/settings",
                json={
                    "dest": str(destination),
                    "degree": "247",
                    "centers": "17,3",
                    "concurrency": 3,
                },
            )
            assert response.status_code == 200
            from ussync.config import Config

            assert Config.load(env).dest == destination
