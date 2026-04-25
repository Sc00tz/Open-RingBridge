import os
import secrets
from contextlib import asynccontextmanager

from fastapi import FastAPI
from fastapi.staticfiles import StaticFiles
from starlette.middleware.sessions import SessionMiddleware

from app.database import SessionLocal, create_tables
from app.models import Setting
from app.routers import api, admin


def _seed_settings() -> None:
    """Populate settings from env vars on first run (DB value always wins)."""
    seeds = {
        "server_url":       os.getenv("SERVER_URL", ""),
        "admin_password":   os.getenv("ADMIN_PASSWORD", "changeme"),
        "registration_key": os.getenv("REGISTRATION_KEY", "changeme"),
    }
    db = SessionLocal()
    try:
        for key, value in seeds.items():
            if not value:
                continue
            exists = db.query(Setting).filter(Setting.key == key).first()
            if not exists:
                db.add(Setting(key=key, value=value))
        db.commit()
    finally:
        db.close()


@asynccontextmanager
async def lifespan(app: FastAPI):
    create_tables()
    _seed_settings()
    yield


app = FastAPI(
    title="RingBridge Server",
    version="1.0.0",
    description="Receives smart ring data and forwards to InfluxDB / MQTT.",
    lifespan=lifespan,
    # Hide API docs behind a path so they're not publicly obvious
    docs_url="/api/docs",
    redoc_url=None,
)

app.add_middleware(
    SessionMiddleware,
    secret_key=os.getenv("SESSION_SECRET", secrets.token_hex(32)),
    max_age=86400,   # 24 h session
)

app.include_router(api.router)
app.include_router(admin.router)


@app.get("/health", tags=["meta"])
def health():
    return {"status": "ok"}
