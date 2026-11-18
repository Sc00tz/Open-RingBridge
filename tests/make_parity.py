"""Generate tests/parity.json: made-up nights, readings and steps, with what core/derived.py and
core/clockfix.py compute from them. The Kotlin tests must produce the same numbers. No personal data.

Run after changing a formula: .venv/bin/python tests/make_parity.py
"""
import datetime as dt
import json
import pathlib
import random
import sys

ROOT = pathlib.Path(__file__).parent.parent
sys.path.insert(0, str(ROOT / "core"))
import clockfix  # noqa: E402
import derived  # noqa: E402

rng = random.Random(7)
BASE = dt.datetime(2026, 3, 1)
EPOCH = dt.datetime(1970, 1, 1)


def ts(when):
    """Unix time as if the clock were UTC. The formulas only compare times, so the zone doesn't matter."""
    return int((when - EPOCH).total_seconds())


def text(when):
    return when.isoformat(" ")


sessions, samples, activity = [], [], []
for day in range(21):
    bed = BASE + dt.timedelta(days=day, hours=22, minutes=rng.randint(0, 150))
    if day == 9:
        continue  # a night without the ring
    parts = 2 if day % 6 == 0 else 1
    start = bed
    for p in range(parts):
        segments, t = [], start
        for _ in range(rng.randint(10, 16)):
            stage = rng.choice(["light", "light", "deep", "rem", "awake"] if day % 4 else ["light", "deep", "rem"])
            seconds = rng.randint(600, 2400)
            segments.append({"stage": stage, "start_ts": ts(t), "start_local": text(t), "seconds": seconds})
            t += dt.timedelta(seconds=seconds)
        sessions.append({"start_ts": ts(start), "end_ts": ts(t), "start_local": text(start), "end_local": text(t), "segments": segments})
        start = t + dt.timedelta(minutes=rng.randint(20, 90))
    # Readings every 10 minutes from the evening before through the next evening, with gaps.
    t = bed - dt.timedelta(hours=14)
    while t < bed + dt.timedelta(hours=20):
        if rng.random() > 0.08:
            asleep = bed <= t <= bed + dt.timedelta(hours=8)
            hr = rng.randint(52, 64) if asleep else rng.randint(66, 105)
            for metric, value in (("heart_rate", hr), ("spo2", rng.randint(93, 99)), ("hrv", rng.randint(18, 70)),
                                  ("respiration", rng.randint(12, 18)), ("bp_systolic", rng.randint(104, 130)),
                                  ("bp_diastolic", rng.randint(64, 84))):
                if not (metric == "hrv" and day == 4):
                    samples.append({"metric": metric, "ts": ts(t), "local": text(t), "value": float(value)})
        t += dt.timedelta(minutes=10)
    for half in range(14, 46):
        start = BASE + dt.timedelta(days=day, minutes=30 * half)
        steps = rng.choice([0, 0, rng.randint(20, 400), rng.randint(400, 2500)]) * (3 if day == 15 else 1)
        if steps:
            activity.append({"start_ts": ts(start), "end_ts": ts(start + dt.timedelta(minutes=30)), "start_local": text(start),
                             "steps": steps, "distance_m": int(steps * 0.72), "kcal": steps // 25})

# Samples are deduplicated per (metric, ts) in the store, and handed over in time order.
seen = set()
samples = sorted((s for s in samples if (s["metric"], s["ts"]) not in seen and not seen.add((s["metric"], s["ts"]))), key=lambda s: s["ts"])
activity.sort(key=lambda a: a["start_ts"])

nights = derived.build_nights(sessions, samples, activity)
dates = [n["id"] for n in nights]
day_samples = lambda d: [s for s in samples if s["local"][:10] == d]
day_activity = lambda d: [a for a in activity if a["start_local"][:10] == d]


def strip(n):
    return {k: n[k] for k in ("id", "start_ts", "end_ts", "sessions", "in_bed", "asleep", "seconds", "efficiency",
                              "vitals", "dip", "steps_during")} | {"score": n["score"]["score"], "band": n["score"]["band"],
                                                                   "confidence": n["score"]["confidence"],
                                                                   "earned": [f["earned"] for f in n["score"]["factors"]],
                                                                   "segments": len(n["segments"])}


def strip_readiness(r):
    if not r["ready"]:
        return {"ready": False}
    return {"ready": True, "score": r["score"], "band": r["band"], "possible": r["possible"], "confidence": r["confidence"],
            "names": [f["name"] for f in r["factors"]], "earned": [f["earned"] for f in r["factors"]], "notes": len(r["notes"])}


# A flat battery, for clockfix: correct, dead and stopped 66 h, charged 18 h, then late until a sync.
lag, prev_set = 66 * 3600, 1_773_000_000
death = prev_set + 20 * 3600
worn = death + 18 * 3600
newest = worn + 28 * 3600
set_ts = newest + lag
clock_samples = list(range(prev_set + 300, death, 300)) + list(range(worn, newest + 1, 300)) + [set_ts + 290]
clock_bins = list(range(prev_set + 1800, death, 1800)) + list(range(worn, newest, 1800))

out = {
    "input": {"sessions": sessions, "samples": samples, "activity": activity},
    "nights": [strip(n) for n in nights],
    "consistency": derived.consistency(nights),
    "recovery": derived.recovery(nights),
    "readiness": {d: strip_readiness(derived.readiness(nights, activity, d)) for d in dates + ["2026-02-01"]},
    "rollup": derived.daily_rollup(samples, activity, nights),
    "day_metrics": {d: [{"id": i["id"], "value": i["value"]} for i in
                        derived.day_metrics(day_samples(d), day_activity(d), next((n for n in nights if n["id"] == d), None))]
                    for d in dates[:6]},
    "clock": {"event": {"set_ts": set_ts, "newest_before": newest}, "previous_set_ts": prev_set,
              "sample_ts": clock_samples, "bin_starts": clock_bins,
              "fix": clockfix.detect({"set_ts": set_ts, "newest_before": newest}, prev_set, clock_samples, clock_bins)},
}
(pathlib.Path(__file__).parent / "parity.json").write_text(json.dumps(out, indent=None, separators=(",", ":")))
print(f"{len(nights)} nights, {len(samples)} samples, {len(activity)} step periods")
