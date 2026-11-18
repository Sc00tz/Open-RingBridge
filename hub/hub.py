"""Open RingBridge hub: a self-hosted server the Android app syncs to. Serves the web dashboard.

It never talks to the ring. The phone reads the ring and uploads the raw history bytes, and the hub
decodes them with the same code as the phone and the Mac bench, so all three agree.

Settings (environment):
  HUB_DATA         data folder (default ./data)
  HUB_HOST         listen address (default 0.0.0.0)
  HUB_PORT         listen port (default 8080)
  HUB_TRUST_PROXY  1 if behind a reverse proxy that sets X-Forwarded-Proto and X-Forwarded-For
  TZ               your time zone. The ring records wall-clock time, so this must match where you live.
"""
import asyncio
import base64
import binascii
import collections
import datetime as dt
import json
import os
import pathlib
import sys
import time

from aiohttp import web

HERE = pathlib.Path(__file__).resolve().parent
ROOT = HERE.parent
sys.path.insert(0, str(ROOT / "core"))

import codec  # noqa: E402
import importer  # noqa: E402
import views  # noqa: E402
from accounts import AccountError, Accounts  # noqa: E402
from store import Store  # noqa: E402

DATA = pathlib.Path(os.environ.get("HUB_DATA", HERE / "data"))
WEB = pathlib.Path(os.environ.get("HUB_WEB", ROOT / "web"))
HOST = os.environ.get("HUB_HOST", "0.0.0.0")
PORT = int(os.environ.get("HUB_PORT", "8080"))
TRUST_PROXY = os.environ.get("HUB_TRUST_PROXY") == "1"
COOKIE = "rb_session"
USER = web.RequestKey("user", object)
MAX_UPLOAD = 32 * 1024 * 1024
VERSION = os.environ.get("HUB_VERSION", "dev")

LOGIN_LIMIT = 10           # failed logins per key
LOGIN_WINDOW = 15 * 60     # seconds


class Hub:
    def __init__(self, data=DATA):
        self.data = pathlib.Path(data)
        (self.data / "users").mkdir(parents=True, exist_ok=True)
        self.accounts = Accounts(self.data / "accounts.db")
        self.accounts.prune()
        self.stores = {}
        self.failures = collections.defaultdict(list)

    def store(self, user):
        if user["id"] not in self.stores:
            folder = self.data / "users" / str(user["id"])
            folder.mkdir(parents=True, exist_ok=True)
            self.stores[user["id"]] = Store(folder / "ring.db")
        return self.stores[user["id"]]

    def raw_dir(self, user):
        return self.data / "users" / str(user["id"]) / "raw"

    # ----- request helpers -----
    @staticmethod
    def secure(request):
        if TRUST_PROXY and request.headers.get("X-Forwarded-Proto"):
            return request.headers["X-Forwarded-Proto"] == "https"
        return request.secure

    @staticmethod
    def client_ip(request):
        if TRUST_PROXY and request.headers.get("X-Forwarded-For"):
            return request.headers["X-Forwarded-For"].split(",")[0].strip()
        return request.remote or "?"

    def limited(self, *keys):
        now = time.time()
        for key in keys:
            self.failures[key] = [t for t in self.failures[key] if now - t < LOGIN_WINDOW]
            if len(self.failures[key]) >= LOGIN_LIMIT:
                return True
        return False

    def failed(self, *keys):
        for key in keys:
            self.failures[key].append(time.time())

    @staticmethod
    async def body(request):
        try:
            return await request.json()
        except (json.JSONDecodeError, UnicodeDecodeError):
            raise web.HTTPBadRequest(text="Expected a JSON body.")

    def set_session(self, request, response, user):
        token = self.accounts.issue(user["id"], "session", request.headers.get("User-Agent", "Browser")[:80])
        response.set_cookie(COOKIE, token, max_age=30 * 86400, httponly=True, samesite="Lax", secure=self.secure(request), path="/")

    # ----- pages -----
    async def index(self, request):
        if self.accounts.count() == 0 or request.get(USER) is None:
            raise web.HTTPFound("/login")
        return web.FileResponse(WEB / "index.html")

    async def login_page(self, _):
        return web.FileResponse(WEB / "login.html")

    async def health(self, _):
        return web.json_response({"ok": True, "version": VERSION})

    # ----- sign in -----
    async def setup_state(self, _):
        return web.json_response({"needs_setup": self.accounts.count() == 0, "version": VERSION})

    async def setup(self, request):
        """Create the first account, which becomes the admin. Only works while there are no accounts."""
        if self.accounts.count():
            raise web.HTTPConflict(text="This hub already has an account. Sign in instead.")
        b = await self.body(request)
        try:
            user = self.accounts.create_user(b.get("username"), b.get("password"), is_admin=True)
        except AccountError as e:
            raise web.HTTPBadRequest(text=str(e))
        response = web.json_response({"user": user})
        self.set_session(request, response, user)
        return response

    async def login(self, request):
        """Browser sign-in: sets a session cookie."""
        b = await self.body(request)
        user = await self.check_login(request, b)
        response = web.json_response({"user": user})
        self.set_session(request, response, user)
        return response

    async def app_login(self, request):
        """App sign-in: returns a token for the Authorization header. Shown once."""
        b = await self.body(request)
        user = await self.check_login(request, b)
        token = self.accounts.issue(user["id"], "app", (b.get("device_name") or "Android app").strip() or "Android app")
        return web.json_response({"token": token, "user": user, "version": VERSION})

    async def check_login(self, request, b):
        keys = (f"ip:{self.client_ip(request)}", f"user:{(b.get('username') or '').strip().lower()}")
        if self.limited(*keys):
            raise web.HTTPTooManyRequests(text="Too many failed sign-ins. Try again in 15 minutes.")
        user = self.accounts.verify(b.get("username"), b.get("password"))
        if user is None:
            self.failed(*keys)
            await asyncio.sleep(0.5)
            raise web.HTTPUnauthorized(text="Wrong username or password.")
        return user

    async def logout(self, request):
        user = request[USER]
        self.accounts.revoke(user["id"], user["token_id"])
        response = web.json_response({"ok": True})
        response.del_cookie(COOKIE, path="/")
        return response

    # ----- account -----
    async def account(self, request):
        user = request[USER]
        return web.json_response({"user": {k: user[k] for k in ("id", "username", "is_admin")},
                                  "apps": self.accounts.apps(user["id"]),
                                  "users": self.accounts.users() if user["is_admin"] else None})

    async def change_password(self, request):
        user = request[USER]
        b = await self.body(request)
        if self.accounts.verify(user["username"], b.get("current")) is None:
            raise web.HTTPForbidden(text="Your current password is wrong.")
        try:
            self.accounts.set_password(user["id"], b.get("new"), keep_token_id=user["token_id"])
        except AccountError as e:
            raise web.HTTPBadRequest(text=str(e))
        return web.json_response({"ok": True})

    async def revoke_app(self, request):
        self.accounts.revoke(request[USER]["id"], int(request.match_info["id"]))
        return await self.account(request)

    async def create_user(self, request):
        if not request[USER]["is_admin"]:
            raise web.HTTPForbidden(text="Only an admin can add accounts.")
        b = await self.body(request)
        try:
            self.accounts.create_user(b.get("username"), b.get("password"), is_admin=bool(b.get("is_admin")))
        except AccountError as e:
            raise web.HTTPBadRequest(text=str(e))
        return await self.account(request)

    # ----- dashboard reads: same shapes as the Mac bench -----
    async def capabilities(self, request):
        user = request[USER]
        return web.json_response({"mode": "hub", "ring": False, "version": VERSION,
                                  "user": {"username": user["username"], "is_admin": bool(user["is_admin"])}})

    async def days(self, request):
        return web.json_response(views.days(self.store(request[USER])))

    async def day(self, request):
        date = request.match_info["date"]
        try:
            dt.date.fromisoformat(date)
        except ValueError:
            raise web.HTTPBadRequest(text="Dates look like 2026-11-18.")
        return web.json_response(views.day(self.store(request[USER]), date))

    async def sleep(self, request):
        return web.json_response(views.sleep(self.store(request[USER]), request.query.get("night")))

    async def trends(self, request):
        try:
            last = int(request.query.get("days", "0"))
        except ValueError:
            raise web.HTTPBadRequest(text="days must be a number.")
        return web.json_response(views.trends(self.store(request[USER]), last))

    async def workouts(self, request):
        return web.json_response(views.workouts(self.store(request[USER])))

    async def device(self, request):
        store = self.store(request[USER])
        return web.json_response({"ring_name": None, "status": store.device(), "last_sync": store.last_sync(),
                                  "measures": {}, "job": None})

    async def clock(self, request):
        return web.json_response(views.clock(self.store(request[USER])))

    async def decide_clock(self, request):
        action = request.match_info["action"]
        if action not in ("apply", "undo", "dismiss"):
            raise web.HTTPNotFound()
        store = self.store(request[USER])
        try:
            store.decide_clock_fix(int(request.match_info["id"]), action)
        except (KeyError, ValueError) as e:
            raise web.HTTPConflict(text=str(e))
        return web.json_response(views.clock(store))

    # ----- uploads from the app -----
    async def upload_sync(self, request):
        """One sync's raw history, as the phone read it from the ring.

        {"set_ts": 1794860456, "device": {...}, "streams": {"heart": {"data": "<base64>", "crc": 12345}, ...}}
        `crc` is the checksum from the ring's end packet. The hub checks it again rather than trusting the app.
        """
        b = await self.body(request)
        try:
            set_ts = int(b["set_ts"])
            streams = {}
            for name, s in (b.get("streams") or {}).items():
                if name not in codec.HISTORY:
                    raise web.HTTPBadRequest(text=f"Unknown stream {name!r}.")
                data = base64.b64decode(s.get("data") or "", validate=True)
                crc = s.get("crc")
                streams[name] = (data, crc is not None and codec.crc16(data) == int(crc))
        except (KeyError, TypeError, ValueError, binascii.Error):
            raise web.HTTPBadRequest(text="Expected set_ts and base64 streams.")
        if abs(set_ts - time.time()) > 400 * 86400:
            raise web.HTTPBadRequest(text="set_ts is not a plausible time.")
        user = request[USER]
        store = self.store(user)
        if isinstance(b.get("device"), dict):
            store.set_device({k: b["device"].get(k) for k in ("device_id", "firmware", "battery_state", "battery", "charging")})
        folder = self.raw_dir(user) / dt.datetime.fromtimestamp(set_ts).strftime("%Y%m%d-%H%M%S")
        result = importer.import_sync(store, set_ts, streams, folder)
        return web.json_response(result)

    async def upload_readings(self, request):
        """Readings and workouts the app recorded itself. Safe to send again.

        {"samples": [{"metric", "local", "value", "origin"}], "workouts": [{"kind", "start_local", ...}]}
        """
        b = await self.body(request)
        store = self.store(request[USER])
        try:
            for s in b.get("samples") or []:
                if s["metric"] not in ALLOWED_METRICS:
                    raise ValueError(f"unknown metric {s['metric']!r}")
                dt.datetime.fromisoformat(s["local"])
                store.add_reading(s["metric"], s["local"], s["value"], s.get("origin", "live"))
            for w in b.get("workouts") or []:
                dt.datetime.fromisoformat(w["start_local"])
                if w.get("kind") is not None and w["kind"] not in views.WORKOUTS:
                    raise ValueError(f"unknown workout kind {w['kind']!r}")
                store.upsert_app_workout(w)
        except (KeyError, TypeError, ValueError) as e:
            store.db.rollback()
            raise web.HTTPBadRequest(text=f"Bad reading: {e}")
        store.commit()
        return web.json_response({"samples": len(b.get("samples") or []), "workouts": len(b.get("workouts") or [])})

    async def me(self, request):
        user = request[USER]
        return web.json_response({"user": {"username": user["username"]}, "version": VERSION})


ALLOWED_METRICS = {"heart_rate", "bp_systolic", "bp_diastolic", "spo2", "glucose", "hrv", "stress"}

PUBLIC = {"/login", "/healthz", "/api/setup", "/api/login", "/api/v1/login"}


def auth_middleware(hub):
    @web.middleware
    async def middleware(request, handler):
        path = request.path
        user = None
        bearer = request.headers.get("Authorization", "")
        if bearer.startswith("Bearer "):
            user = hub.accounts.authenticate(bearer[7:].strip(), "app")
            if user is None:
                raise web.HTTPUnauthorized(text="That app token isn't valid. Sign in again.")
        else:
            user = hub.accounts.authenticate(request.cookies.get(COOKIE), "session")
            # A cookie rides along on requests from any site, so changes must come from this hub's own pages.
            if request.method not in ("GET", "HEAD", "OPTIONS") and path.startswith("/api/") and path not in ("/api/v1/login",):
                origin = request.headers.get("Origin") or request.headers.get("Referer")
                if not origin or origin.split("://", 1)[-1].split("/", 1)[0] != request.host:
                    raise web.HTTPForbidden(text="Requests that change things must come from this hub's own pages.")
        request[USER] = user
        if user is None and path not in PUBLIC and not path.startswith("/web/") and path != "/":
            raise web.HTTPUnauthorized(text="Sign in first.")
        response = await handler(request)
        if path.startswith("/api/"):
            response.headers["Cache-Control"] = "no-store"
        response.headers.setdefault("X-Content-Type-Options", "nosniff")
        response.headers.setdefault("Referrer-Policy", "same-origin")
        response.headers.setdefault("X-Frame-Options", "DENY")
        return response
    return middleware


def build(data=DATA):
    hub = Hub(data)
    app = web.Application(middlewares=[auth_middleware(hub)], client_max_size=MAX_UPLOAD)
    app.add_routes([
        web.get("/", hub.index), web.get("/login", hub.login_page), web.get("/healthz", hub.health),
        web.get("/api/setup", hub.setup_state), web.post("/api/setup", hub.setup),
        web.post("/api/login", hub.login), web.post("/api/logout", hub.logout),
        web.get("/api/account", hub.account), web.post("/api/account/password", hub.change_password),
        web.delete("/api/account/apps/{id}", hub.revoke_app), web.post("/api/users", hub.create_user),
        web.get("/api/capabilities", hub.capabilities),
        web.get("/api/days", hub.days), web.get("/api/day/{date}", hub.day), web.get("/api/sleep", hub.sleep),
        web.get("/api/trends", hub.trends), web.get("/api/workouts", hub.workouts), web.get("/api/device", hub.device),
        web.get("/api/clock", hub.clock), web.post("/api/clock/{id}/{action}", hub.decide_clock),
        web.post("/api/v1/login", hub.app_login), web.get("/api/v1/me", hub.me),
        web.post("/api/v1/sync", hub.upload_sync), web.post("/api/v1/readings", hub.upload_readings),
    ])
    app.router.add_static("/web", WEB)
    return app


if __name__ == "__main__":
    web.run_app(build(), host=HOST, port=PORT, print=lambda *_: print(f"Open RingBridge hub {VERSION} listening on {HOST}:{PORT}"))
