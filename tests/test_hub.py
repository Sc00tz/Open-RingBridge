import base64
import json
import pathlib
import sys
import tempfile
import unittest

from aiohttp.test_utils import TestClient, TestServer

ROOT = pathlib.Path(__file__).parent.parent
sys.path.insert(0, str(ROOT / "core"))
sys.path.insert(0, str(ROOT / "hub"))
import codec  # noqa: E402
import hub  # noqa: E402

VECTORS = json.loads((ROOT / "tests" / "vectors.json").read_text())
HEART = bytes.fromhex(next(v["hex"] for v in VECTORS["history"] if v["name"] == "heart"))
PASSWORD = "correct horse battery"


class Hub(unittest.IsolatedAsyncioTestCase):
    async def asyncSetUp(self):
        self.dir = tempfile.TemporaryDirectory()
        self.client = TestClient(TestServer(hub.build(pathlib.Path(self.dir.name))))
        await self.client.start_server()
        self.origin = {"Origin": f"http://{self.client.host}:{self.client.port}"}

    async def asyncTearDown(self):
        await self.client.close()
        self.dir.cleanup()

    async def setup_admin(self, client=None):
        r = await (client or self.client).post("/api/setup", json={"username": "travis", "password": PASSWORD}, headers=self.origin)
        self.assertEqual(r.status, 200, await r.text())

    async def app_token(self, username="travis", password=PASSWORD):
        r = await self.client.post("/api/v1/login", json={"username": username, "password": password, "device_name": "Pixel"})
        self.assertEqual(r.status, 200, await r.text())
        return {"Authorization": f"Bearer {(await r.json())['token']}"}

    async def test_first_account_then_setup_closes(self):
        r = await self.client.get("/api/setup")
        self.assertTrue((await r.json())["needs_setup"])
        await self.setup_admin()
        r = await self.client.post("/api/setup", json={"username": "x", "password": PASSWORD}, headers=self.origin)
        self.assertEqual(r.status, 409)

    async def test_reads_need_sign_in(self):
        await self.setup_admin()
        self.client.session.cookie_jar.clear()
        self.assertEqual((await self.client.get("/api/days")).status, 401)
        r = await self.client.get("/", allow_redirects=False)
        self.assertEqual((r.status, r.headers["Location"]), (302, "/login"))

    async def test_cookie_changes_need_same_origin(self):
        await self.setup_admin()   # signs in this client
        self.assertEqual((await self.client.get("/api/days")).status, 200)
        r = await self.client.post("/api/logout", headers={"Origin": "https://evil.example"})
        self.assertEqual(r.status, 403)
        self.assertEqual((await self.client.post("/api/logout", headers=self.origin)).status, 200)
        self.assertEqual((await self.client.get("/api/days")).status, 401)

    async def test_wrong_password_and_lockout(self):
        await self.setup_admin()
        for _ in range(hub.LOGIN_LIMIT):
            r = await self.client.post("/api/v1/login", json={"username": "travis", "password": "nope"})
            self.assertEqual(r.status, 401)
        r = await self.client.post("/api/v1/login", json={"username": "travis", "password": PASSWORD})
        self.assertEqual(r.status, 429)

    async def test_upload_sync_checks_crc_itself(self):
        await self.setup_admin()
        auth = await self.app_token()
        body = {"set_ts": 1773459000, "device": {"battery": 80, "firmware": "1.13", "device_id": 163, "charging": False},
                "streams": {"heart": {"data": base64.b64encode(HEART).decode(), "crc": codec.crc16(HEART)},
                            "blood": {"data": base64.b64encode(b"\x01\x02").decode(), "crc": 0}}}
        r = await self.client.post("/api/v1/sync", json=body, headers=auth)
        self.assertEqual(r.status, 200, await r.text())
        result = await r.json()
        self.assertEqual(result["added"], {"heart": 1})
        self.assertEqual(result["failed"], ["blood"])
        day = await (await self.client.get("/api/day/2026-03-14", headers=auth)).json()
        self.assertEqual(day["latest"]["heart_rate"]["v"], 62)
        self.assertEqual((await (await self.client.get("/api/device", headers=auth)).json())["status"]["battery"], 80)

    async def test_readings_and_workouts_upload_idempotently(self):
        await self.setup_admin()
        auth = await self.app_token()
        body = {"samples": [{"metric": "heart_rate", "local": "2026-03-14 12:00:00", "value": 71, "origin": "live"}],
                "workouts": [{"kind": "walk", "ring_code": 8, "start_local": "2026-03-14 12:00:00", "end_local": "2026-03-14 12:20:00",
                              "steps": 1800, "hr_avg": 98}]}
        for _ in range(2):
            self.assertEqual((await self.client.post("/api/v1/readings", json=body, headers=auth)).status, 200)
        w = await (await self.client.get("/api/workouts", headers=auth)).json()
        self.assertEqual([(x["kind"], x["steps"]) for x in w["workouts"]], [("walk", 1800)])
        bad = {"samples": [{"metric": "rm -rf", "local": "2026-03-14 12:00:00", "value": 1}]}
        self.assertEqual((await self.client.post("/api/v1/readings", json=bad, headers=auth)).status, 400)

    async def test_people_see_only_their_own_data(self):
        await self.setup_admin()
        r = await self.client.post("/api/users", json={"username": "sam", "password": PASSWORD}, headers=self.origin)
        self.assertEqual(r.status, 200, await r.text())
        travis, sam = await self.app_token(), await self.app_token("sam")
        body = {"samples": [{"metric": "heart_rate", "local": "2026-03-14 12:00:00", "value": 71, "origin": "live"}]}
        await self.client.post("/api/v1/readings", json=body, headers=travis)
        self.assertEqual((await (await self.client.get("/api/days", headers=sam)).json())["days"], [])
        self.assertEqual((await (await self.client.get("/api/days", headers=travis)).json())["days"], ["2026-03-14"])
        r = await self.client.post("/api/users", json={"username": "x", "password": PASSWORD}, headers=sam)
        self.assertEqual(r.status, 403)

    async def test_removed_phone_is_signed_out(self):
        await self.setup_admin()
        auth = await self.app_token()
        account = await (await self.client.get("/api/account")).json()
        app_id = account["apps"][0]["id"]
        self.assertEqual((await self.client.delete(f"/api/account/apps/{app_id}", headers=self.origin)).status, 200)
        self.assertEqual((await self.client.get("/api/days", headers=auth)).status, 401)


if __name__ == "__main__":
    unittest.main()
