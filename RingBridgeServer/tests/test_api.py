"""End-to-end API tests via FastAPI TestClient: auth, readings dedup, sleep round-trip."""


def auth(token):
    return {"Authorization": f"Bearer {token}"}


# ── Health & auth ───────────────────────────────────────────────────────────

def test_health(client):
    assert client.get("/health").json() == {"status": "ok"}


def test_register_requires_valid_key(client):
    r = client.post("/api/v1/devices/register",
                    json={"name": "X", "registration_key": "wrong"})
    assert r.status_code == 403


def test_readings_require_auth(client):
    r = client.post("/api/v1/readings", json={"readings": []})
    assert r.status_code == 403  # no bearer header → HTTPBearer rejects


def test_bad_token_is_rejected(client):
    r = client.post("/api/v1/readings", json={"readings": []}, headers=auth("not-a-real-token"))
    assert r.status_code == 401


# ── Readings ingest + dedup ───────────────────────────────────────────────────

def test_ingest_and_query_readings(client, device_token):
    token, _ = device_token
    payload = {"readings": [
        {"timestamp": 1000, "type": "hr", "value": 60},
        {"timestamp": 1000, "type": "spo2", "value": 98},
    ]}
    r = client.post("/api/v1/readings", json=payload, headers=auth(token))
    assert r.status_code == 201
    assert r.json()["received"] == 2

    rows = client.get("/api/v1/readings", headers=auth(token)).json()
    assert len(rows) == 2
    assert {row["type"] for row in rows} == {"hr", "spo2"}


def test_readings_dedup_within_batch(client, device_token):
    token, _ = device_token
    payload = {"readings": [
        {"timestamp": 1000, "type": "hr", "value": 60},
        {"timestamp": 1000, "type": "hr", "value": 60},   # exact dup
        {"timestamp": 1000, "type": "spo2", "value": 98},
    ]}
    client.post("/api/v1/readings", json=payload, headers=auth(token))
    rows = client.get("/api/v1/readings", headers=auth(token)).json()
    assert len(rows) == 2  # the duplicate hr was collapsed


def test_readings_dedup_across_retried_posts(client, device_token):
    token, _ = device_token
    batch = {"readings": [{"timestamp": 1000, "type": "hr", "value": 60}]}
    client.post("/api/v1/readings", json=batch, headers=auth(token))
    client.post("/api/v1/readings", json=batch, headers=auth(token))  # app retry
    rows = client.get("/api/v1/readings", headers=auth(token)).json()
    assert len(rows) == 1


def test_readings_are_device_scoped(client):
    a = client.post("/api/v1/devices/register",
                    json={"name": "A", "registration_key": "test-reg-key"}).json()
    b = client.post("/api/v1/devices/register",
                    json={"name": "B", "registration_key": "test-reg-key"}).json()
    client.post("/api/v1/readings",
                json={"readings": [{"timestamp": 1, "type": "hr", "value": 50}]},
                headers=auth(a["token"]))
    # Device B must not see device A's readings.
    assert client.get("/api/v1/readings", headers=auth(b["token"])).json() == []


# ── Sleep round-trip + upsert ─────────────────────────────────────────────────

SLEEP = {
    "start_ms": 1700000000000, "end_ms": 1700028800000,
    "total_sleep_ms": 25200000, "deep_sleep_ms": 5400000,
    "light_sleep_ms": 14400000, "rem_sleep_ms": 5400000,
    "nap_ms": 0, "wake_ms": 1800000,
}


def test_sleep_ingest_and_query(client, device_token):
    token, _ = device_token
    r = client.post("/api/v1/sleep", json={"sessions": [SLEEP]}, headers=auth(token))
    assert r.status_code == 201
    assert r.json()["upserted"] == 1

    rows = client.get("/api/v1/sleep", headers=auth(token)).json()
    assert len(rows) == 1
    assert rows[0]["deep_sleep_ms"] == 5400000


def test_sleep_upsert_is_idempotent(client, device_token):
    token, _ = device_token
    client.post("/api/v1/sleep", json={"sessions": [SLEEP]}, headers=auth(token))
    client.post("/api/v1/sleep", json={"sessions": [SLEEP]}, headers=auth(token))
    rows = client.get("/api/v1/sleep", headers=auth(token)).json()
    assert len(rows) == 1  # same start_ms → updated in place, not duplicated


def test_sleep_upsert_updates_values(client, device_token):
    token, _ = device_token
    client.post("/api/v1/sleep", json={"sessions": [SLEEP]}, headers=auth(token))
    updated = {**SLEEP, "deep_sleep_ms": 9999}
    client.post("/api/v1/sleep", json={"sessions": [updated]}, headers=auth(token))
    rows = client.get("/api/v1/sleep", headers=auth(token)).json()
    assert len(rows) == 1
    assert rows[0]["deep_sleep_ms"] == 9999


def test_sleep_query_time_filter(client, device_token):
    token, _ = device_token
    early = {**SLEEP, "start_ms": 1000}
    late = {**SLEEP, "start_ms": 9_000_000_000_000}
    client.post("/api/v1/sleep", json={"sessions": [early, late]}, headers=auth(token))
    rows = client.get("/api/v1/sleep?start=5000", headers=auth(token)).json()
    assert [r["start_ms"] for r in rows] == [9_000_000_000_000]


# ── Admin login (bcrypt) ──────────────────────────────────────────────────────

def test_admin_login_with_seeded_password(client):
    # follow_redirects=False so we can see the 302 → /admin/ on success.
    r = client.post("/admin/login", data={"password": "test-admin-pw"}, follow_redirects=False)
    assert r.status_code == 302
    assert r.headers["location"] == "/admin/"


def test_admin_login_wrong_password(client):
    r = client.post("/admin/login", data={"password": "nope"})
    assert "Incorrect password" in r.text


def test_seeded_admin_password_is_hashed(client):
    # The seeded password must be stored as a bcrypt hash, never plaintext.
    import app.database as database
    from app.models import Setting
    db = database.SessionLocal()
    try:
        row = db.query(Setting).filter(Setting.key == "admin_password").first()
        assert row is not None
        assert row.value.startswith("$2")
        assert row.value != "test-admin-pw"
    finally:
        db.close()
