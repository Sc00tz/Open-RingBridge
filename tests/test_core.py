import asyncio
import datetime as dt
import json
import pathlib
import struct
import sys
import tempfile
import unittest

ROOT = pathlib.Path(__file__).parent.parent
sys.path.insert(0, str(ROOT / "core"))
sys.path.insert(0, str(ROOT / "bench"))
import codec
import derived
import ringlink
from store import Store, to_ts

VECTORS = json.loads((pathlib.Path(__file__).parent / "vectors.json").read_text())


def session(start, end, segments):
    return {"start_ts": to_ts(start), "end_ts": to_ts(end), "start_local": start, "end_local": end,
            "segments": [{"stage": s, "seconds": n} for s, n in segments]}


class Codec(unittest.TestCase):
    def test_crc_check_value(self):
        self.assertEqual(codec.crc16(b"123456789"), 0x29B1)

    def test_frames(self):
        for v in VECTORS["frames"]:
            raw = codec.frame(v["code"], bytes.fromhex(v["payload"]))
            self.assertEqual(raw.hex(), v["hex"])
            self.assertEqual(codec.parse(raw), (v["code"], bytes.fromhex(v["payload"]), True))

    def test_bad_crc_detected(self):
        self.assertFalse(codec.parse(bytes.fromhex("0200080047436fed"))[2])

    def test_history_records(self):
        for v in VECTORS["history"]:
            with self.subTest(v["name"]):
                got = codec.decode_history(v["name"], bytes.fromhex(v["hex"]))
                self.assertEqual([list(s) for s in got["samples"]], v["samples"])
                self.assertEqual(got["activity"], v["activity"])
                self.assertEqual(got["sleep"], v["sleep"])
                self.assertEqual(got["workouts"], v.get("workouts", []))

    def test_assembler_handles_split_and_joined_frames(self):
        a, b = codec.frame(0x0200, b"GC"), codec.frame(0x021B)
        assembler = codec.Assembler()
        self.assertEqual(assembler.feed(a[:3]), [])
        self.assertEqual(assembler.feed(a[3:] + b), [a, b])


class Stores(unittest.TestCase):
    def setUp(self):
        self.dir = tempfile.TemporaryDirectory()
        self.store = Store(pathlib.Path(self.dir.name) / "t.db")

    def tearDown(self):
        self.store.db.close()
        self.dir.cleanup()

    def test_repeat_import_adds_nothing(self):
        decoded = {"samples": [("heart_rate", "2026-03-14 01:30:00", 60)], "activity": [], "sleep": []}
        self.assertEqual(self.store.add_decoded(decoded), 1)
        self.assertEqual(self.store.add_decoded(decoded), 0)

    def test_combined_does_not_double_a_dedicated_reading(self):
        self.store.add_decoded({"samples": [("heart_rate", "2026-03-14 01:30:00", 60)], "activity": [], "sleep": []})
        self.store.add_decoded({"samples": [("heart_rate", "2026-03-14 01:30:20", 61)], "activity": [], "sleep": []})
        self.assertEqual(len(self.store.samples()), 1)

    def test_history_replaces_live_reading(self):
        import datetime as dt
        self.store.add_live("spo2", 99, dt.datetime(2026, 3, 14, 1, 30, 0))
        self.store.add_decoded({"samples": [("spo2", "2026-03-14 01:30:40", 98)], "activity": [], "sleep": []})
        rows = self.store.samples()
        self.assertEqual([(r["value"], r["origin"]) for r in rows], [(98, "history")])

    def test_sources_stay_separate(self):
        self.store.add_decoded({"samples": [("heart_rate", "2026-03-14 01:30:00", 60)], "activity": [], "sleep": []})
        self.store.add_decoded({"samples": [("heart_rate", "2026-03-14 01:30:00", 58)], "activity": [], "sleep": []}, source="bed")
        self.assertEqual(len(self.store.samples()), 1)
        self.assertEqual(self.store.samples(source="bed")[0]["value"], 58)


    def test_ring_record_fills_in_our_workout_and_repeats_add_nothing(self):
        import datetime as dt
        wid = self.store.start_workout("run", 1, dt.datetime(2026, 3, 14, 1, 30))
        self.store.finish_workout(wid, derived.workout_totals(None, None, [120]), "done", False, dt.datetime(2026, 3, 14, 2, 0))
        record = {"start": "2026-03-14 01:30:05", "end": "2026-03-14 01:59:50", "steps": 3000, "distance_m": 2100, "kcal": 150,
                  "ring_code": 1, "hr_avg": 128, "hr_min": 95, "hr_max": 160}
        decoded = {"samples": [], "activity": [], "sleep": [], "workouts": [record]}
        self.assertEqual(self.store.add_decoded(decoded), 0)
        self.assertEqual(self.store.add_decoded(decoded), 0)
        rows = self.store.workouts()
        self.assertEqual([(r["kind"], r["steps"], r["hr_avg"]) for r in rows], [("run", 3000, 128)])

    def test_ring_only_workout_is_added_once(self):
        record = {"start": "2026-03-14 01:30:00", "end": "2026-03-14 02:00:00", "steps": 10, "distance_m": 7, "kcal": 1,
                  "ring_code": 3, "hr_avg": 0, "hr_min": 0, "hr_max": 0}
        decoded = {"samples": [], "activity": [], "sleep": [], "workouts": [record]}
        self.assertEqual(self.store.add_decoded(decoded), 1)
        self.assertEqual(self.store.add_decoded(decoded), 0)
        self.assertIsNone(self.store.workouts()[0]["hr_avg"])

    def test_new_sleep_session_is_counted_once(self):
        session = {"start": "2026-03-14 01:13:39", "end": "2026-03-14 07:55:52",
                   "segments": [{"start": "2026-03-14 01:13:39", "stage": "light", "seconds": 1420}]}
        decoded = {"samples": [], "activity": [], "sleep": [session], "workouts": []}
        self.assertEqual(self.store.add_decoded(decoded), 1)
        self.assertEqual(self.store.add_decoded(decoded), 0)

    def test_interrupted_workout_is_closed_and_remembered_for_the_ring(self):
        wid = self.store.start_workout("walk", 8)
        self.store.close_interrupted_workouts()
        self.assertEqual(self.store.open_ring_workouts(), [{"id": wid, "ring_code": 8}])
        self.store.mark_ring_closed(wid)
        self.assertEqual(self.store.open_ring_workouts(), [])


class FakeLink:
    """Stands in for the Bluetooth link and plays back a scripted workout."""
    sent = []
    script = []
    reply = b"\x00"
    drop_once = False

    def __init__(self):
        self._connected = False
        self._script = list(FakeLink.script)

    @property
    def connected(self):
        return self._connected

    async def __aenter__(self):
        self._connected = True
        return self

    async def __aexit__(self, *exc):
        self._connected = False

    async def send(self, code, payload=b""):
        FakeLink.sent.append((code, bytes(payload)))

    async def ask(self, code, payload=b"", timeout=4.0):
        await self.send(code, payload)
        return FakeLink.reply

    async def set_live(self, enable):
        await self.send(0x0309, bytes([int(enable), 1, 2]))

    async def recv(self, timeout):
        if FakeLink.drop_once:
            FakeLink.drop_once = False
            self._connected = False
            raise asyncio.TimeoutError
        if self._script:
            code, body = self._script.pop(0)
            return code, body, True
        await asyncio.sleep(0)
        raise asyncio.TimeoutError


class Workouts(unittest.TestCase):
    def setUp(self):
        self.dir = tempfile.TemporaryDirectory()
        self.store = Store(pathlib.Path(self.dir.name) / "t.db")
        FakeLink.sent, FakeLink.script, FakeLink.reply, FakeLink.drop_once = [], [], b"\x00", False

    def tearDown(self):
        self.store.db.close()
        self.dir.cleanup()

    def run_workout(self, kind="run", stop_when=lambda: False):
        job = {}
        calls = {"n": 0}

        def stopped():
            calls["n"] += 1
            return stop_when() or calls["n"] > 20
        result = asyncio.run(ringlink.workout(kind, self.store, self.dir.name, lambda **f: job.update(f), stopped, FakeLink))
        return result, job

    def test_counts_only_what_changed_during_the_workout_and_stops_the_ring(self):
        sport = lambda s, d, k: (0x0600, struct.pack("<HHH", s, d, k))
        FakeLink.script = [sport(5000, 3000, 200), (0x0601, bytes([110])), (0x0601, bytes([0])), sport(5100, 3070, 205), (0x0601, bytes([130]))]
        result, job = self.run_workout()
        self.assertEqual(result["totals"], {"steps": 100, "distance_m": 70, "kcal": 5, "hr_avg": 120, "hr_min": 110, "hr_max": 130})
        self.assertEqual(FakeLink.sent[0], (0x030C, bytes([1, 1])))
        self.assertIn((0x030C, bytes([0, 1])), FakeLink.sent)
        row = self.store.workouts()[0]
        self.assertEqual((row["status"], row["ring_open"], row["steps"]), ("done", 0, 100))
        self.assertEqual([s["origin"] for s in self.store.samples()], ["workout"])

    def test_refusal_records_nothing(self):
        FakeLink.reply = b"\x01"
        with self.assertRaises(ringlink.RingError):
            self.run_workout()
        self.assertEqual(self.store.workouts(), [])

    def test_reconnects_after_a_drop_and_keeps_going(self):
        FakeLink.drop_once = True
        FakeLink.script = [(0x0601, bytes([100]))]
        result, _ = self.run_workout()
        self.assertEqual(result["totals"]["hr_avg"], 100)
        self.assertEqual(self.store.workouts()[0]["status"], "done")

    def test_ring_ending_the_workout_finishes_it(self):
        FakeLink.script = [(0x040C, bytes([0, 1]))]
        result, _ = self.run_workout()
        self.assertTrue(result["ended_by_ring"])

    def test_every_workout_has_a_distinct_code(self):
        codes = [w["code"] for w in ringlink.WORKOUTS.values()]
        self.assertEqual(len(codes), len(set(codes)))


def night(day, asleep=7 * 3600, **vitals):
    """A night ending at 7:00 on `day`."""
    end = f"{day} 07:00:00"
    start = dt.datetime.fromisoformat(end) - dt.timedelta(seconds=asleep)
    n = derived.group_nights([session(start.isoformat(" "), end, [("deep", asleep // 4), ("rem", asleep // 4), ("light", asleep - asleep // 2)])])[0]
    n["vitals"] = vitals
    n["dip"] = None
    n["score"] = derived.sleep_score(n, vitals)
    return n


class RingClock(unittest.TestCase):
    """A flat battery: worn and correct, dead 66 h with the clock stopped, charged, then worn with a late clock."""
    LAG = 66 * 3600

    def setUp(self):
        self.dir = tempfile.TemporaryDirectory()
        self.store = Store(pathlib.Path(self.dir.name) / "t.db")
        base = to_ts("2026-03-09 09:50:00")
        self.prev_set = base
        self.death = base + 20 * 3600                    # ring labels: correct up to here
        self.restart_worn = self.death + 18 * 3600       # 18 h on the charger and in a drawer, ring time
        self.newest = self.restart_worn + 28 * 3600      # last label before the clock is set again
        self.set_ts = self.newest + self.LAG              # real time of that sync
        self.labels = list(range(base + 300, self.death, 300)) + list(range(self.restart_worn, self.newest + 1, 300))

    def tearDown(self):
        self.store.db.close()
        self.dir.cleanup()

    def decoded(self, labels):
        local = lambda t: dt.datetime.fromtimestamp(t).isoformat(" ")
        return {"samples": [("heart_rate", local(t), 70) for t in labels], "activity": [], "sleep": [], "workouts": []}

    def sync_events(self):
        self.store.add_decoded(self.decoded(self.labels))
        self.store.record_clock_set(self.prev_set, self.prev_set - 200)
        self.store.record_clock_set(self.set_ts, self.newest)

    def test_detects_lag_and_range(self):
        self.sync_events()
        self.assertEqual(self.store.check_clock(), 0)   # no record after the set yet: can't tell
        self.store.add_decoded(self.decoded([self.set_ts + 290]))
        self.assertEqual(self.store.check_clock(), 1)
        fix = self.store.clock_fixes()[0]
        self.assertEqual(fix["offset"], self.LAG)
        self.assertGreater(fix["from_ts"], self.death - 300)   # the last correct record stays put
        self.assertLessEqual(fix["from_ts"], self.restart_worn)
        self.assertEqual(fix["to_ts"], self.newest)
        self.assertEqual(self.store.check_clock(), 0)   # asked once

    def test_apply_undo_and_reimport(self):
        self.sync_events()
        self.store.add_decoded(self.decoded([self.set_ts + 290]))
        self.store.check_clock()
        fix_id = self.store.clock_fixes()[0]["id"]
        before = sorted(r["ts"] for r in self.store.samples())
        self.store.decide_clock_fix(fix_id, "apply")
        moved = sorted(r["ts"] for r in self.store.samples())
        self.assertEqual(len(moved), len(before))
        self.assertEqual(max(t for t in moved if t <= self.set_ts), self.newest + self.LAG)
        self.store.add_decoded(self.decoded(self.labels))   # the ring sends the late records again
        self.assertEqual(sorted(r["ts"] for r in self.store.samples()), moved)
        self.store.decide_clock_fix(fix_id, "undo")
        self.assertEqual(sorted(r["ts"] for r in self.store.samples()), before)
        with self.assertRaises(ValueError):
            self.store.decide_clock_fix(fix_id, "undo")

    def test_ordinary_gap_is_not_a_clock_problem(self):
        # Worn right up to the sync: newest record is minutes old.
        self.store.add_decoded(self.decoded([self.set_ts - 200, self.set_ts + 290]))
        self.store.record_clock_set(self.prev_set, None)
        self.store.record_clock_set(self.set_ts, self.set_ts - 200)
        self.assertEqual(self.store.check_clock(), 0)

    def test_first_ever_sync_is_never_flagged(self):
        # Months unworn, then put on just before the first sync: there's no earlier correct clock to compare.
        self.store.add_decoded(self.decoded([self.prev_set - 90 * 86400, self.set_ts + 290]))
        self.store.record_clock_set(self.set_ts, self.prev_set - 90 * 86400)
        self.assertEqual(self.store.check_clock(), 0)


class Readiness(unittest.TestCase):
    def days(self, count, resting=60, hrv=30):
        base = dt.date(2026, 3, 1)
        return [night((base + dt.timedelta(days=i)).isoformat(), resting_hr=resting, hrv_avg=hrv) for i in range(count)]

    def test_no_sleep_means_no_score_rather_than_zero(self):
        result = derived.readiness([], [], "2026-03-14")
        self.assertFalse(result["ready"])
        self.assertNotIn("score", result)

    def test_first_night_uses_sleep_alone_and_says_so(self):
        nights = self.days(1)
        result = derived.readiness(nights, [], nights[0]["id"])
        self.assertEqual([f["name"] for f in result["factors"]], ["Last night's sleep"])
        self.assertEqual(result["possible"], 35)
        self.assertEqual(result["confidence"], "low")

    def test_baseline_factors_appear_after_three_earlier_nights(self):
        nights = self.days(4)
        names = [f["name"] for f in derived.readiness(nights, [], nights[3]["id"])["factors"]]
        self.assertIn("Resting heart rate", names)
        self.assertIn("HRV balance", names)
        self.assertIn("Sleep balance", names)

    def test_raised_heart_rate_and_low_hrv_lower_the_score(self):
        steady = self.days(5)
        worse = self.days(4) + [night("2026-03-05", resting_hr=70, hrv_avg=21)]
        good, bad = (derived.readiness(n, [], "2026-03-05") for n in (steady, worse))
        points = {f["name"]: f["earned"] for f in bad["factors"]}
        self.assertEqual((points["Resting heart rate"], points["HRV balance"]), (0, 0))
        self.assertLess(bad["score"], good["score"])

    def test_yesterday_too_little_or_too_much_loses_points(self):
        def steps_on(value):
            base = [{"start_local": f"2026-03-{d:02d} 12:00:00", "steps": 8000} for d in range(1, 4)]
            return base + [{"start_local": "2026-03-04 12:00:00", "steps": value}]
        points = lambda value: derived._yesterday_factor(steps_on(value), "2026-03-05")[2]
        self.assertEqual(points(8000), 1)
        self.assertEqual(points(12000), 1)
        self.assertLess(points(2000), 1)
        self.assertEqual(points(24000), 0)

    def test_totals_without_counters_stay_empty(self):
        self.assertEqual(derived.workout_totals(None, None, [])["steps"], None)


class Derived(unittest.TestCase):
    def test_sessions_of_one_night_are_joined_with_the_gap_as_awake(self):
        nights = derived.group_nights([
            session("2026-03-13 23:00:00", "2026-03-14 01:00:00", [("light", 7200)]),
            session("2026-03-14 01:30:00", "2026-03-14 07:00:00", [("deep", 9900), ("rem", 9900)]),
        ])
        self.assertEqual(len(nights), 1)
        n = nights[0]
        self.assertEqual(n["id"], "2026-03-14")
        self.assertEqual(n["asleep"], 27000)
        self.assertEqual(n["seconds"]["awake"], 1800)
        self.assertAlmostEqual(n["efficiency"], 27000 / 28800)

    def test_separate_nights_stay_separate(self):
        nights = derived.group_nights([
            session("2026-03-13 23:00:00", "2026-03-14 07:00:00", [("light", 28800)]),
            session("2026-03-14 23:00:00", "2026-03-15 07:00:00", [("light", 28800)]),
        ])
        self.assertEqual([n["id"] for n in nights], ["2026-03-14", "2026-03-15"])

    def test_score_rescales_when_inputs_are_missing(self):
        night = derived.group_nights([session("2026-03-13 23:00:00", "2026-03-14 07:00:00",
                                               [("deep", 7200), ("rem", 7200), ("light", 14400)])])[0]
        result = derived.sleep_score(night, {})
        self.assertEqual(result["score"], 100)  # every available factor at full points
        self.assertEqual(result["band"], "Optimal")
        self.assertEqual(result["confidence"], "low")
        self.assertEqual(sum(f["weight"] for f in result["factors"]), 70)

    def test_score_bands_match_the_app_text(self):
        self.assertEqual([derived.band(x) for x in (85, 84, 70, 69, 60, 59)],
                         ["Optimal", "Good", "Good", "Average", "Average", "Poor"])

    def test_short_night_is_flagged(self):
        night = derived.group_nights([session("2026-03-14 05:00:00", "2026-03-14 07:00:00", [("light", 7200)])])[0]
        self.assertEqual(derived.sleep_score(night, {})["confidence"], "low")

    def test_pressure_metrics(self):
        rows = [{"metric": "bp_systolic", "ts": 100, "local": "2026-03-14 12:00:00", "value": 120},
                {"metric": "bp_diastolic", "ts": 100, "local": "2026-03-14 12:00:00", "value": 80},
                {"metric": "heart_rate", "ts": 110, "local": "2026-03-14 12:00:10", "value": 70}]
        items = {i["id"]: i["value"] for i in derived.day_metrics(rows, [])}
        self.assertEqual(items, {"pulse_pressure": 40, "map": 93, "rate_pressure": 8400})

    def test_no_inputs_no_items(self):
        self.assertEqual(derived.day_metrics([], []), [])

    def test_recovery_waits_for_a_baseline(self):
        self.assertFalse(derived.recovery([])["ready"])


if __name__ == "__main__":
    unittest.main()
