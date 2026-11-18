"""SQLite store. The tables map directly onto Android Room entities.

Every row carries a `source`, so other devices can be added later without changing the schema:
a bed's heart rate would be ("sleepnumber", "heart_rate", ...) next to ("ring", "heart_rate", ...).

Times are stored twice: `local` is the wall clock the ring recorded, and `ts` is the matching
Unix time, for lining up with sources that report UTC.
"""
import datetime as dt
import json
import pathlib
import sqlite3
import time

import clockfix

SCHEMA = """
CREATE TABLE IF NOT EXISTS sample (
  source TEXT NOT NULL, metric TEXT NOT NULL, ts INTEGER NOT NULL, local TEXT NOT NULL,
  value REAL NOT NULL, origin TEXT NOT NULL,
  PRIMARY KEY (source, metric, ts));
CREATE INDEX IF NOT EXISTS sample_local ON sample (local);
CREATE TABLE IF NOT EXISTS activity (
  source TEXT NOT NULL, start_ts INTEGER NOT NULL, end_ts INTEGER NOT NULL, start_local TEXT NOT NULL,
  steps INTEGER NOT NULL, distance_m INTEGER NOT NULL, kcal INTEGER NOT NULL,
  PRIMARY KEY (source, start_ts));
CREATE TABLE IF NOT EXISTS sleep_session (
  source TEXT NOT NULL, start_ts INTEGER NOT NULL, end_ts INTEGER NOT NULL,
  start_local TEXT NOT NULL, end_local TEXT NOT NULL,
  PRIMARY KEY (source, start_ts));
CREATE TABLE IF NOT EXISTS sleep_segment (
  source TEXT NOT NULL, session_start_ts INTEGER NOT NULL, start_ts INTEGER NOT NULL,
  start_local TEXT NOT NULL, stage TEXT NOT NULL, seconds INTEGER NOT NULL,
  PRIMARY KEY (source, start_ts));
CREATE TABLE IF NOT EXISTS workout (
  id INTEGER PRIMARY KEY AUTOINCREMENT, source TEXT NOT NULL, kind TEXT, ring_code INTEGER,
  start_ts INTEGER NOT NULL, end_ts INTEGER, start_local TEXT NOT NULL, end_local TEXT,
  steps INTEGER, distance_m INTEGER, kcal INTEGER, hr_avg INTEGER, hr_min INTEGER, hr_max INTEGER,
  status TEXT NOT NULL, ring_open INTEGER NOT NULL DEFAULT 0);
CREATE TABLE IF NOT EXISTS device_status (source TEXT PRIMARY KEY, updated_ts INTEGER NOT NULL, json TEXT NOT NULL);
CREATE TABLE IF NOT EXISTS sync_log (ts INTEGER NOT NULL, status TEXT NOT NULL, detail TEXT NOT NULL);
CREATE TABLE IF NOT EXISTS clock_set (
  source TEXT NOT NULL, set_ts INTEGER NOT NULL, newest_before INTEGER,
  PRIMARY KEY (source, set_ts));
CREATE TABLE IF NOT EXISTS clock_fix (
  id INTEGER PRIMARY KEY AUTOINCREMENT, source TEXT NOT NULL, event_ts INTEGER NOT NULL,
  from_ts INTEGER NOT NULL, to_ts INTEGER NOT NULL, offset INTEGER NOT NULL,
  status TEXT NOT NULL, basis TEXT NOT NULL, decided_ts INTEGER);
"""
SAME_READING_SECONDS = 60


def to_ts(local):
    return int(dt.datetime.fromisoformat(local).astimezone().timestamp())


def to_local(ts):
    return dt.datetime.fromtimestamp(ts).isoformat(" ")


class Store:
    def __init__(self, path):
        pathlib.Path(path).parent.mkdir(parents=True, exist_ok=True)
        self.db = sqlite3.connect(path)
        self.db.row_factory = sqlite3.Row
        self.db.executescript(SCHEMA)

    def is_empty(self):
        return self.db.execute("SELECT NOT EXISTS (SELECT 1 FROM sample)").fetchone()[0] == 1

    def add_decoded(self, decoded, source="ring"):
        """Import one decoded history stream. Safe to repeat, since nothing is deleted from the ring."""
        added = 0
        # The ring keeps sending records it stamped with a wrong clock until they age out, so fix them on every import.
        decoded = clockfix.shift_decoded(decoded, self.clock_fixes(source, "applied"), to_ts, to_local)
        for metric, local, value in decoded["samples"]:
            ts = to_ts(local)
            # The stored record replaces the live reading of the same measurement.
            self.db.execute("DELETE FROM sample WHERE source=? AND metric=? AND origin='live' AND ts BETWEEN ? AND ?",
                            (source, metric, ts - 120, ts + 120))
            nearby = self.db.execute("SELECT 1 FROM sample WHERE source=? AND metric=? AND ts BETWEEN ? AND ? LIMIT 1",
                                     (source, metric, ts - SAME_READING_SECONDS, ts + SAME_READING_SECONDS)).fetchone()
            if nearby:
                continue
            self.db.execute("INSERT INTO sample VALUES (?,?,?,?,?,'history')", (source, metric, ts, local, value))
            added += 1
        for a in decoded["activity"]:
            # A half-hour bin still in progress grows between reads, so the newest read wins.
            self.db.execute("INSERT OR REPLACE INTO activity VALUES (?,?,?,?,?,?,?)",
                            (source, to_ts(a["start"]), to_ts(a["end"]), a["start"], a["steps"], a["distance_m"], a["kcal"]))
        for s in decoded["sleep"]:
            start = to_ts(s["start"])
            added += self.db.execute("SELECT NOT EXISTS (SELECT 1 FROM sleep_session WHERE source=? AND start_ts=?)",
                                     (source, start)).fetchone()[0]
            self.db.execute("INSERT OR REPLACE INTO sleep_session VALUES (?,?,?,?,?)",
                            (source, start, to_ts(s["end"]), s["start"], s["end"]))
            for g in s["segments"]:
                self.db.execute("INSERT OR REPLACE INTO sleep_segment VALUES (?,?,?,?,?,?)",
                                (source, start, to_ts(g["start"]), g["start"], g["stage"], g["seconds"]))
        added_workouts = sum(self.merge_ring_workout(w, source) for w in decoded.get("workouts", []))
        self.db.commit()
        return added + added_workouts

    def add_live(self, metric, value, now=None, source="ring", origin="live"):
        now = now or dt.datetime.now().replace(microsecond=0)
        local = now.isoformat(" ")
        self.db.execute("INSERT OR REPLACE INTO sample VALUES (?,?,?,?,?,?)", (source, metric, to_ts(local), local, value, origin))
        self.db.commit()

    def add_reading(self, metric, local, value, origin, source="ring"):
        """A reading taken by an app (a spot measurement or workout), with the app's own clock."""
        if origin not in ("live", "workout"):
            raise ValueError(f"unknown origin {origin!r}")
        self.db.execute("INSERT OR REPLACE INTO sample VALUES (?,?,?,?,?,?)", (source, metric, to_ts(local), local, float(value), origin))

    def upsert_app_workout(self, w, source="ring"):
        """A workout recorded by an app. The same workout uploaded again replaces the earlier copy."""
        start = to_ts(w["start_local"])
        end = to_ts(w["end_local"]) if w.get("end_local") else None
        fields = (w.get("kind"), w.get("ring_code"), start, end, w["start_local"], w.get("end_local"), w.get("steps"),
                  w.get("distance_m"), w.get("kcal"), w.get("hr_avg"), w.get("hr_min"), w.get("hr_max"), w.get("status", "done"))
        row = self.db.execute("SELECT id FROM workout WHERE source=? AND start_ts=? AND COALESCE(kind,'')=COALESCE(?,'')",
                              (source, start, w.get("kind"))).fetchone()
        if row:
            self.db.execute("UPDATE workout SET kind=?, ring_code=?, start_ts=?, end_ts=?, start_local=?, end_local=?, steps=?, distance_m=?, "
                            "kcal=?, hr_avg=?, hr_min=?, hr_max=?, status=? WHERE id=?", (*fields, row["id"]))
        else:
            self.db.execute("INSERT INTO workout (kind, ring_code, start_ts, end_ts, start_local, end_local, steps, distance_m, kcal, "
                            "hr_avg, hr_min, hr_max, status, source) VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?)", (*fields, source))

    def commit(self):
        self.db.commit()

    def samples(self, date=None, start_ts=None, end_ts=None, source="ring"):
        query, args = "SELECT metric, ts, local, value, origin FROM sample WHERE source=?", [source]
        if date:
            query += " AND local LIKE ?"
            args.append(f"{date}%")
        if start_ts is not None:
            query += " AND ts >= ? AND ts <= ?"
            args += [start_ts, end_ts]
        return [dict(r) for r in self.db.execute(query + " ORDER BY ts", args)]

    def activity(self, date=None, source="ring"):
        query, args = "SELECT * FROM activity WHERE source=?", [source]
        if date:
            query += " AND start_local LIKE ?"
            args.append(f"{date}%")
        return [dict(r) for r in self.db.execute(query + " ORDER BY start_ts", args)]

    def sleep(self, source="ring"):
        sessions = [dict(r) for r in self.db.execute("SELECT * FROM sleep_session WHERE source=? ORDER BY start_ts", (source,))]
        for s in sessions:
            s["segments"] = [dict(r) for r in self.db.execute(
                "SELECT start_ts, start_local, stage, seconds FROM sleep_segment WHERE source=? AND session_start_ts=? ORDER BY start_ts",
                (source, s["start_ts"]))]
        return sessions

    def days(self, source="ring"):
        rows = self.db.execute(
            "SELECT DISTINCT substr(local,1,10) d FROM sample WHERE source=? UNION "
            "SELECT DISTINCT substr(start_local,1,10) FROM activity WHERE source=? UNION "
            "SELECT DISTINCT substr(end_local,1,10) FROM sleep_session WHERE source=? ORDER BY d", (source, source, source))
        return [r[0] for r in rows]

    def start_workout(self, kind, ring_code, now=None, source="ring"):
        now = now or dt.datetime.now().replace(microsecond=0)
        local = now.isoformat(" ")
        cursor = self.db.execute(
            "INSERT INTO workout (source, kind, ring_code, start_ts, start_local, status, ring_open) VALUES (?,?,?,?,?,'recording',1)",
            (source, kind, ring_code, to_ts(local), local))
        self.db.commit()
        return cursor.lastrowid

    def finish_workout(self, workout_id, totals, status, ring_open, now=None):
        now = now or dt.datetime.now().replace(microsecond=0)
        local = now.isoformat(" ")
        self.db.execute(
            "UPDATE workout SET end_ts=?, end_local=?, steps=?, distance_m=?, kcal=?, hr_avg=?, hr_min=?, hr_max=?, status=?, ring_open=? WHERE id=?",
            (to_ts(local), local, totals["steps"], totals["distance_m"], totals["kcal"], totals["hr_avg"], totals["hr_min"],
             totals["hr_max"], status, int(ring_open), workout_id))
        self.db.commit()

    def merge_ring_workout(self, w, source="ring"):
        """Attach the ring's own record to our workout overlapping it, else add it. Returns 1 if new."""
        start, end = to_ts(w["start"]), to_ts(w["end"])
        totals = (w["steps"], w["distance_m"], w["kcal"], w["hr_avg"] or None, w["hr_min"] or None, w["hr_max"] or None)
        row = self.db.execute(
            "SELECT id FROM workout WHERE source=? AND start_ts <= ? AND COALESCE(end_ts, start_ts) >= ? ORDER BY start_ts LIMIT 1",
            (source, end, start)).fetchone()
        if row:
            self.db.execute("UPDATE workout SET steps=?, distance_m=?, kcal=?, hr_avg=COALESCE(?,hr_avg), hr_min=COALESCE(?,hr_min), "
                            "hr_max=COALESCE(?,hr_max) WHERE id=?", (*totals, row["id"]))
            return 0
        self.db.execute(
            "INSERT INTO workout (source, ring_code, start_ts, end_ts, start_local, end_local, steps, distance_m, kcal, hr_avg, hr_min, hr_max, status) "
            "VALUES (?,?,?,?,?,?,?,?,?,?,?,?,'ring')", (source, w["ring_code"], start, end, w["start"], w["end"], *totals))
        return 1

    def close_interrupted_workouts(self):
        """A workout still "recording" at startup means the server stopped mid-way. The ring may still be in workout mode."""
        for w in self.db.execute("SELECT id, start_ts, start_local FROM workout WHERE status='recording'").fetchall():
            last = self.db.execute("SELECT ts, local FROM sample WHERE origin='workout' AND ts >= ? ORDER BY ts DESC LIMIT 1", (w["start_ts"],)).fetchone()
            self.db.execute("UPDATE workout SET status='lost', end_ts=?, end_local=? WHERE id=?",
                            (last["ts"] if last else w["start_ts"], last["local"] if last else w["start_local"], w["id"]))
        self.db.commit()

    def open_ring_workouts(self, source="ring"):
        return [dict(r) for r in self.db.execute("SELECT id, ring_code FROM workout WHERE source=? AND ring_open=1 AND status!='recording'", (source,))]

    def mark_ring_closed(self, workout_id):
        self.db.execute("UPDATE workout SET ring_open=0 WHERE id=?", (workout_id,))
        self.db.commit()

    def workouts(self, date=None, limit=30, source="ring"):
        query, args = "SELECT * FROM workout WHERE source=? AND status!='recording'", [source]
        if date:
            query += " AND start_local LIKE ?"
            args.append(f"{date}%")
        return [dict(r) for r in self.db.execute(query + " ORDER BY start_ts DESC LIMIT ?", (*args, limit))]

    def set_device(self, status, source="ring"):
        self.db.execute("INSERT OR REPLACE INTO device_status VALUES (?,?,?)", (source, int(time.time()), json.dumps(status)))
        self.db.commit()

    def device(self, source="ring"):
        row = self.db.execute("SELECT * FROM device_status WHERE source=?", (source,)).fetchone()
        return {"updated_ts": row["updated_ts"], **json.loads(row["json"])} if row else None

    def log_sync(self, status, detail):
        self.db.execute("INSERT INTO sync_log VALUES (?,?,?)", (int(time.time()), status, json.dumps(detail)))
        self.db.commit()

    # ----- Ring clock. See clockfix.py.
    def record_clock_set(self, set_ts, newest_before, source="ring"):
        self.db.execute("INSERT OR REPLACE INTO clock_set VALUES (?,?,?)", (source, int(set_ts), newest_before))
        self.db.commit()

    def check_clock(self, source="ring"):
        """Look for a clock that was behind at any sync not yet checked. Returns how many new proposals."""
        events = [dict(r) for r in self.db.execute("SELECT set_ts, newest_before FROM clock_set WHERE source=? ORDER BY set_ts", (source,))]
        seen = {r[0] for r in self.db.execute("SELECT event_ts FROM clock_fix WHERE source=?", (source,))}
        sample_ts = [r[0] for r in self.db.execute("SELECT DISTINCT ts FROM sample WHERE source=? AND origin='history' ORDER BY ts", (source,))]
        bin_starts = [r[0] for r in self.db.execute("SELECT start_ts FROM activity WHERE source=?", (source,))]
        found = 0
        for previous, event in zip([None] + events, events):
            if event["set_ts"] in seen:
                continue
            fix = clockfix.detect(event, previous and previous["set_ts"], sample_ts, bin_starts)
            if fix:
                self.db.execute("INSERT INTO clock_fix (source, event_ts, from_ts, to_ts, offset, status, basis) VALUES (?,?,?,?,?,'proposed',?)",
                                (source, event["set_ts"], fix["from_ts"], fix["to_ts"], fix["offset"], json.dumps(fix["basis"])))
                found += 1
        self.db.commit()
        return found

    def clock_fixes(self, source="ring", status=None):
        query, args = "SELECT * FROM clock_fix WHERE source=?", [source]
        if status:
            query += " AND status=?"
            args.append(status)
        rows = [dict(r) for r in self.db.execute(query + " ORDER BY event_ts", args)]
        for r in rows:
            r["basis"] = json.loads(r["basis"])
        return rows

    def decide_clock_fix(self, fix_id, action):
        """action: apply, undo or dismiss. Only ring history moves; readings taken by this app kept their own clock."""
        fix = self.db.execute("SELECT * FROM clock_fix WHERE id=?", (fix_id,)).fetchone()
        if fix is None:
            raise KeyError(fix_id)
        wanted = {"apply": ("proposed", "applied"), "undo": ("applied", "undone"), "dismiss": ("proposed", "dismissed")}[action]
        if fix["status"] != wanted[0]:
            raise ValueError(f"This correction is {fix['status']}, so it can't be {action}ed.")
        if action == "apply":
            self._move(fix["source"], fix["from_ts"], fix["to_ts"], fix["offset"])
        elif action == "undo":
            self._move(fix["source"], fix["from_ts"] + fix["offset"], fix["to_ts"] + fix["offset"], -fix["offset"])
        self.db.execute("UPDATE clock_fix SET status=?, decided_ts=? WHERE id=?", (wanted[1], int(time.time()), fix_id))
        self.db.commit()

    def _move(self, source, low, high, delta):
        rows = self.db.execute("SELECT metric, ts, value FROM sample WHERE source=? AND origin='history' AND ts BETWEEN ? AND ?",
                               (source, low, high)).fetchall()
        self.db.execute("DELETE FROM sample WHERE source=? AND origin='history' AND ts BETWEEN ? AND ?", (source, low, high))
        self.db.executemany("INSERT OR IGNORE INTO sample VALUES (?,?,?,?,?,'history')",
                            [(source, r["metric"], r["ts"] + delta, to_local(r["ts"] + delta), r["value"]) for r in rows])
        rows = self.db.execute("SELECT * FROM activity WHERE source=? AND start_ts BETWEEN ? AND ?", (source, low, high)).fetchall()
        self.db.execute("DELETE FROM activity WHERE source=? AND start_ts BETWEEN ? AND ?", (source, low, high))
        self.db.executemany("INSERT OR REPLACE INTO activity VALUES (?,?,?,?,?,?,?)",
                            [(source, r["start_ts"] + delta, r["end_ts"] + delta, to_local(r["start_ts"] + delta),
                              r["steps"], r["distance_m"], r["kcal"]) for r in rows])
        sessions = self.db.execute("SELECT * FROM sleep_session WHERE source=? AND start_ts BETWEEN ? AND ?", (source, low, high)).fetchall()
        for s in sessions:
            segments = self.db.execute("SELECT * FROM sleep_segment WHERE source=? AND session_start_ts=?", (source, s["start_ts"])).fetchall()
            self.db.execute("DELETE FROM sleep_segment WHERE source=? AND session_start_ts=?", (source, s["start_ts"]))
            self.db.execute("DELETE FROM sleep_session WHERE source=? AND start_ts=?", (source, s["start_ts"]))
            new_start = s["start_ts"] + delta
            self.db.execute("INSERT OR REPLACE INTO sleep_session VALUES (?,?,?,?,?)",
                            (source, new_start, s["end_ts"] + delta, to_local(new_start), to_local(s["end_ts"] + delta)))
            self.db.executemany("INSERT OR REPLACE INTO sleep_segment VALUES (?,?,?,?,?,?)",
                                [(source, new_start, g["start_ts"] + delta, to_local(g["start_ts"] + delta), g["stage"], g["seconds"]) for g in segments])
        self.db.execute("UPDATE workout SET start_ts=start_ts+?, end_ts=end_ts+? WHERE source=? AND status='ring' AND start_ts BETWEEN ? AND ?",
                        (delta, delta, source, low, high))
        for w in self.db.execute("SELECT id, start_ts, end_ts FROM workout WHERE source=? AND status='ring' AND start_ts BETWEEN ? AND ?",
                                 (source, low + delta, high + delta)).fetchall():
            self.db.execute("UPDATE workout SET start_local=?, end_local=? WHERE id=?", (to_local(w["start_ts"]), to_local(w["end_ts"]), w["id"]))

    def last_sync(self):
        row = self.db.execute("SELECT * FROM sync_log ORDER BY ts DESC LIMIT 1").fetchone()
        return {"ts": row["ts"], "status": row["status"], "detail": json.loads(row["detail"])} if row else None
