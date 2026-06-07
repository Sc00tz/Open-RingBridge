"""Shared pytest fixtures. Each test gets an isolated SQLite file and a TestClient."""
import os
import tempfile

import pytest


@pytest.fixture()
def client(monkeypatch):
    """A TestClient backed by a fresh temp database.

    DATABASE_PATH is set before importing the app so the engine binds to the temp
    file, and app modules are reloaded to pick it up between tests.
    """
    import importlib

    fd, path = tempfile.mkstemp(suffix=".db")
    os.close(fd)
    monkeypatch.setenv("DATABASE_PATH", path)
    monkeypatch.setenv("ADMIN_PASSWORD", "test-admin-pw")
    monkeypatch.setenv("REGISTRATION_KEY", "test-reg-key")
    monkeypatch.setenv("SESSION_SECRET", "test-secret")

    # Reload the DB + app modules so the new DATABASE_PATH takes effect.
    import app.database as database
    importlib.reload(database)
    import app.routers.admin as admin
    importlib.reload(admin)
    import app.routers.api as api
    importlib.reload(api)
    import app.main as main
    importlib.reload(main)

    from fastapi.testclient import TestClient
    with TestClient(main.app) as c:
        yield c

    os.unlink(path)


@pytest.fixture()
def device_token(client):
    """Register a device and return (token, device_id)."""
    r = client.post("/api/v1/devices/register",
                    json={"name": "Test Ring", "registration_key": "test-reg-key"})
    assert r.status_code == 201, r.text
    body = r.json()
    return body["token"], body["device_id"]
