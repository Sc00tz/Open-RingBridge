"""Tests for the existing-DB dedup migration in app.database._ensure_dedup_indexes."""
import importlib
import os
import sqlite3
import tempfile


def _make_old_db(path: str) -> None:
    """Create a pre-migration schema (no unique indexes) with duplicate rows."""
    c = sqlite3.connect(path)
    c.execute("CREATE TABLE readings (id INTEGER PRIMARY KEY, device_id VARCHAR, "
              "timestamp BIGINT, type VARCHAR, value FLOAT)")
    c.execute("CREATE TABLE sleep_sessions (id INTEGER PRIMARY KEY, device_id VARCHAR, "
              "start_ms BIGINT, end_ms BIGINT, total_sleep_ms BIGINT, deep_sleep_ms BIGINT, "
              "light_sleep_ms BIGINT, rem_sleep_ms BIGINT, nap_ms BIGINT, wake_ms BIGINT)")
    c.executemany(
        "INSERT INTO readings (device_id,timestamp,type,value) VALUES (?,?,?,?)",
        [("ring-1", 1000, "hr", 60.0),
         ("ring-1", 1000, "hr", 60.0),   # dup of key (ring-1,1000,hr)
         ("ring-1", 1000, "hr", 61.0),   # dup of key (ring-1,1000,hr)
         ("ring-1", 2000, "hr", 62.0)],  # distinct
    )
    c.executemany(
        "INSERT INTO sleep_sessions (device_id,start_ms,end_ms) VALUES (?,?,?)",
        [("ring-1", 5000, 6000), ("ring-1", 5000, 6000)],  # dup
    )
    c.commit()
    c.close()


def _run_migration(path: str):
    """Reload app.database bound to `path` and run create_tables() (which migrates)."""
    os.environ["DATABASE_PATH"] = path
    import app.database as database
    importlib.reload(database)
    database.create_tables()
    return database


def test_migration_dedupes_and_indexes_existing_db():
    fd, path = tempfile.mkstemp(suffix=".db")
    os.close(fd)
    try:
        _make_old_db(path)
        _run_migration(path)

        c = sqlite3.connect(path)
        # Duplicates collapsed, keeping the lowest id (value 60.0, not 61.0).
        rows = c.execute("SELECT timestamp,type,value FROM readings ORDER BY id").fetchall()
        assert rows == [(1000, "hr", 60.0), (2000, "hr", 62.0)]
        assert c.execute("SELECT COUNT(*) FROM sleep_sessions").fetchone()[0] == 1

        # Unique indexes now exist.
        idx = {r[0] for r in c.execute(
            "SELECT name FROM sqlite_master WHERE type='index' AND name LIKE 'uq_%'")}
        assert idx == {"uq_reading_dev_ts_type", "uq_sleep_device_start"}
        c.close()
    finally:
        os.environ.pop("DATABASE_PATH", None)
        os.unlink(path)


def test_migration_is_idempotent():
    fd, path = tempfile.mkstemp(suffix=".db")
    os.close(fd)
    try:
        _make_old_db(path)
        _run_migration(path)
        # Running again must not raise and must not change row counts.
        db = _run_migration(path)
        db.create_tables()

        c = sqlite3.connect(path)
        assert c.execute("SELECT COUNT(*) FROM readings").fetchone()[0] == 2
        c.close()
    finally:
        os.environ.pop("DATABASE_PATH", None)
        os.unlink(path)


def test_index_blocks_duplicate_insert_after_migration():
    fd, path = tempfile.mkstemp(suffix=".db")
    os.close(fd)
    try:
        _make_old_db(path)
        _run_migration(path)

        c = sqlite3.connect(path)
        # A raw duplicate insert must now fail at the DB level.
        try:
            c.execute("INSERT INTO readings (device_id,timestamp,type,value) "
                      "VALUES ('ring-1',2000,'hr',99.0)")
            c.commit()
            raised = False
        except sqlite3.IntegrityError:
            raised = True
        assert raised
        c.close()
    finally:
        os.environ.pop("DATABASE_PATH", None)
        os.unlink(path)
