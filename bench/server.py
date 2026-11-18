"""Local test server: a JSON API and the web UI. Listens on this Mac only.

The JSON responses are the screens' view models, so an Android ViewModel can produce the same shapes.
Run from the repo root: .venv/bin/python bench/server.py    then open http://127.0.0.1:8787
"""
import asyncio
import datetime as dt
import pathlib
import sys

from aiohttp import web

HERE = pathlib.Path(__file__).parent
sys.path.insert(0, str(HERE.parent / "core"))

import codec
import derived
import ringlink
import views
from store import Store

DATA = HERE / "data"
WEB = HERE.parent / "web"
CAPTURES = HERE.parent / "tools" / "captures"
HOST, PORT = "127.0.0.1", 8787


def import_captures(store):
    """Seed an empty database from the saved test captures, so the UI works without the ring."""
    imported = {}
    for name in codec.HISTORY_ORDER:
        path = CAPTURES / f"{name}.bin"
        if path.exists():
            imported[name] = store.add_decoded(codec.decode_history(name, path.read_bytes()))
    if imported:
        store.log_sync("imported-captures", {"added": imported, "failed": []})
    return imported


class App:
    def __init__(self):
        self.store = Store(DATA / "ring.db")
        if self.store.is_empty():
            import_captures(self.store)
        self.store.close_interrupted_workouts()
        self.job = None
        self.task = None

    # ----- reads: see core/views.py -----
    async def days(self, _):
        return web.json_response(views.days(self.store))

    async def day(self, request):
        return web.json_response(views.day(self.store, request.match_info["date"]))

    async def sleep(self, request):
        return web.json_response(views.sleep(self.store, request.query.get("night")))

    async def trends(self, request):
        return web.json_response(views.trends(self.store, int(request.query.get("days", "0"))))

    async def workouts(self, _):
        return web.json_response(views.workouts(self.store))

    async def device(self, _):
        measures = {k: {"label": m["label"], "seconds": m["seconds"]} for k, m in ringlink.MEASURES.items()}
        return web.json_response({"ring_name": ringlink.RING_NAME, "status": self.store.device(),
                                  "last_sync": self.store.last_sync(), "measures": measures, "job": self.public_job()})

    # ----- jobs: one ring operation at a time -----
    def public_job(self):
        return {k: v for k, v in self.job.items() if not k.startswith("_")} if self.job else None

    def start(self, kind, label, work):
        if self.job and self.job["state"] == "running":
            raise web.HTTPConflict(text="The ring is busy with another task.")
        self.job = {"kind": kind, "label": label, "state": "running", "message": "Connecting", "values": {},
                    "result": None, "started_ts": dt.datetime.now().timestamp(), "_cancel": False}

        async def runner():
            try:
                self.job["result"] = await work(self.job)
                # Stopping a workout is how it finishes, and its data is kept.
                self.job["state"] = "cancelled" if self.job["_cancel"] and kind not in ringlink.WORKOUTS else "done"
            except ringlink.RingError as e:
                self.job.update(state="error", message=str(e))
            except Exception as e:  # Bluetooth errors shouldn't take the server down
                self.job.update(state="error", message=f"Bluetooth error: {e}")
        self.task = asyncio.create_task(runner())
        return web.json_response(self.public_job(), status=202)

    async def start_sync(self, _):
        async def work(job):
            def progress(text):
                job["message"] = text
            return await ringlink.sync(self.store, DATA / "raw", progress)
        return self.start("sync", "Sync", work)

    async def start_measure(self, request):
        kind = request.match_info["kind"]
        if kind not in ringlink.MEASURES:
            raise web.HTTPNotFound()

        async def work(job):
            def on_value(values):
                job["values"] = values
                job["message"] = "Measuring"
            job["message"] = "Connecting"
            return await ringlink.measure(kind, self.store, on_value, lambda: job["_cancel"])
        return self.start(kind, ringlink.MEASURES[kind]["label"], work)

    async def start_workout(self, request):
        kind = request.match_info["kind"]
        if kind not in ringlink.WORKOUTS:
            raise web.HTTPNotFound()

        async def work(job):
            job.update(heart=None, totals=None, points=[])
            return await ringlink.workout(kind, self.store, DATA / "raw", lambda **fields: job.update(fields), lambda: job["_cancel"])
        return self.start(kind, ringlink.WORKOUTS[kind]["label"], work)

    async def cancel(self, _):
        if self.job and self.job["state"] == "running":
            self.job["_cancel"] = True
        return web.json_response(self.public_job())

    async def capabilities(self, _):
        return web.json_response({"mode": "bench", "ring": True})

    async def get_job(self, _):
        return web.json_response(self.public_job())

    # ----- ring clock corrections -----
    async def clock(self, _):
        return web.json_response(views.clock(self.store))

    async def decide_clock(self, request):
        if self.job and self.job["state"] == "running":
            raise web.HTTPConflict(text="Wait for the ring to finish its current task.")
        action = request.match_info["action"]
        if action not in ("apply", "undo", "dismiss"):
            raise web.HTTPNotFound()
        try:
            self.store.decide_clock_fix(int(request.match_info["id"]), action)
        except KeyError:
            raise web.HTTPNotFound()
        except ValueError as e:
            raise web.HTTPConflict(text=str(e))
        return await self.clock(request)


@web.middleware
async def local_only(request, handler):
    """Stop other websites in this browser from driving the ring through localhost."""
    allowed = {f"{HOST}:{PORT}", f"localhost:{PORT}"}
    origin = request.headers.get("Origin")
    if request.host not in allowed or (origin and origin.split("://")[-1] not in allowed):
        raise web.HTTPForbidden()
    response = await handler(request)
    response.headers["Cache-Control"] = "no-store"
    return response


def build():
    a = App()
    app = web.Application(middlewares=[local_only])
    app.add_routes([
        web.get("/api/capabilities", a.capabilities),
        web.get("/api/days", a.days), web.get("/api/day/{date}", a.day), web.get("/api/sleep", a.sleep),
        web.get("/api/trends", a.trends), web.get("/api/device", a.device), web.get("/api/job", a.get_job), web.get("/api/workouts", a.workouts),
        web.post("/api/workout/{kind}", a.start_workout),
        web.get("/api/clock", a.clock), web.post("/api/clock/{id}/{action}", a.decide_clock),
        web.post("/api/sync", a.start_sync), web.post("/api/measure/{kind}", a.start_measure), web.post("/api/cancel", a.cancel),
    ])

    async def index(_):
        return web.FileResponse(WEB / "index.html")
    app.router.add_get("/", index)
    app.router.add_static("/web", WEB)
    return app


if __name__ == "__main__":
    web.run_app(build(), host=HOST, port=PORT, print=lambda *_: print(f"Open http://{HOST}:{PORT}"))
