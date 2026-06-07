import logging
import os
from sqlalchemy import create_engine, text
from sqlalchemy.orm import sessionmaker, Session
from app.models import Base

logger = logging.getLogger("ringbridge")

DATABASE_PATH = os.getenv("DATABASE_PATH", "/data/ringbridge.db")
DATABASE_URL  = f"sqlite:///{DATABASE_PATH}"

engine       = create_engine(DATABASE_URL, connect_args={"check_same_thread": False})
SessionLocal = sessionmaker(autocommit=False, autoflush=False, bind=engine)


def create_tables() -> None:
    Base.metadata.create_all(bind=engine)
    _ensure_dedup_indexes()


def _ensure_dedup_indexes() -> None:
    """Add the unique dedup indexes to pre-existing databases.

    SQLAlchemy's create_all() only creates *missing* tables — it never alters an
    existing one. Deployments created before the UniqueConstraints were introduced
    therefore lack them, so the app's ON CONFLICT DO NOTHING ingestion has nothing
    to conflict against. This backfills the indexes idempotently.

    A UNIQUE index creation fails if duplicates already exist, so we delete the
    surviving duplicate rows (keeping the lowest id) before creating each index.
    """
    statements = [
        # readings: unique on (device_id, timestamp, type)
        (
            "readings",
            """DELETE FROM readings WHERE id NOT IN (
                   SELECT MIN(id) FROM readings GROUP BY device_id, timestamp, type
               )""",
            "CREATE UNIQUE INDEX IF NOT EXISTS uq_reading_dev_ts_type "
            "ON readings (device_id, timestamp, type)",
        ),
        # sleep_sessions: unique on (device_id, start_ms)
        (
            "sleep_sessions",
            """DELETE FROM sleep_sessions WHERE id NOT IN (
                   SELECT MIN(id) FROM sleep_sessions GROUP BY device_id, start_ms
               )""",
            "CREATE UNIQUE INDEX IF NOT EXISTS uq_sleep_device_start "
            "ON sleep_sessions (device_id, start_ms)",
        ),
    ]

    with engine.begin() as conn:
        for table, dedup_sql, index_sql in statements:
            # Skip tables that don't exist yet (fresh DB already has the constraint).
            exists = conn.execute(
                text("SELECT name FROM sqlite_master WHERE type='table' AND name=:t"),
                {"t": table},
            ).first()
            if not exists:
                continue
            removed = conn.execute(text(dedup_sql)).rowcount
            if removed and removed > 0:
                logger.warning("Removed %d duplicate row(s) from %s before indexing", removed, table)
            conn.execute(text(index_sql))


def get_db():
    """FastAPI dependency — yields a database session then closes it."""
    db = SessionLocal()
    try:
        yield db
    finally:
        db.close()
