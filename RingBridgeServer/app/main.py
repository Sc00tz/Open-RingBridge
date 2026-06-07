import logging
import os
import secrets
from contextlib import asynccontextmanager

from fastapi import FastAPI
from fastapi.staticfiles import StaticFiles
from starlette.middleware.sessions import SessionMiddleware

from app.database import SessionLocal, create_tables
from app.models import Setting
from app.routers import api, admin
from app.security import hash_password

logger = logging.getLogger("ringbridge")

INSECURE_DEFAULTS = {"changeme", "change-this-secret-in-production", ""}


def _seed_settings() -> None:
    """Populate settings from env vars on first run (DB value always wins).

    The admin password is stored as a bcrypt hash; the registration key and
    server URL are stored verbatim.
    """
    raw_seeds = {
        "server_url":       os.getenv("SERVER_URL", ""),
        "admin_password":   os.getenv("ADMIN_PASSWORD", "changeme"),
        "registration_key": os.getenv("REGISTRATION_KEY", "changeme"),
    }
    db = SessionLocal()
    try:
        for key, value in raw_seeds.items():
            if not value:
                continue
            exists = db.query(Setting).filter(Setting.key == key).first()
            if not exists:
                stored = hash_password(value) if key == "admin_password" else value
                db.add(Setting(key=key, value=stored))
        db.commit()
    finally:
        db.close()


def _warn_insecure_config() -> None:
    """Log a prominent warning if the deployment is using default/insecure secrets.

    We warn rather than refuse to start so the documented `docker-compose up`
    quickstart still works out of the box on a trusted LAN — but the operator
    is told, loudly, to change these before exposing the server.
    """
    problems = []
    if os.getenv("ADMIN_PASSWORD", "changeme") in INSECURE_DEFAULTS:
        problems.append("ADMIN_PASSWORD is unset or 'changeme'")
    if os.getenv("REGISTRATION_KEY", "changeme") in INSECURE_DEFAULTS:
        problems.append("REGISTRATION_KEY is unset or 'changeme'")
    if os.getenv("SESSION_SECRET", "") in INSECURE_DEFAULTS:
        problems.append("SESSION_SECRET is unset or default (login sessions are forgeable)")

    if problems:
        banner = "  !!  ".join(problems)
        logger.warning(
            "\n" + "!" * 72 +
            "\n!!  INSECURE CONFIGURATION — change before exposing this server:\n!!  "
            + banner +
            "\n" + "!" * 72
        )


@asynccontextmanager
async def lifespan(app: FastAPI):
    create_tables()
    _seed_settings()
    _warn_insecure_config()
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
