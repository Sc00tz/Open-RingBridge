"""Screen view models, shared by the Mac bench and the hub. Each returns plain JSON-ready dicts.

On Android, ViewModels produce the same shapes from the same tables.
"""
import datetime as dt
import statistics

import derived

# Sport codes are unconfirmed: the app compiles them into Dart, and these come from memory of the
# vendor's public SDK. A wrong code should only mislabel the ring's own record. See PROTOCOL.md.
WORKOUTS = {
    "walk": {"label": "Walk", "code": 8},
    "run": {"label": "Run", "code": 1},
    "bike": {"label": "Bike", "code": 3},
}


def public_night(n):
    return {k: n[k] for k in ("id", "start_ts", "end_ts", "start_local", "end_local", "sessions", "in_bed", "asleep",
                              "seconds", "efficiency", "segments", "vitals", "dip", "score", "steps_during")}


def nights(store):
    return derived.build_nights(store.sleep(), store.samples(), store.activity())


def days(store, today=None):
    found = store.days()
    today = (today or dt.date.today()).isoformat()
    return {"days": found, "today": today, "default": today if today in found else (found[-1] if found else None)}


def _series(rows):
    series = {}
    for s in rows:
        series.setdefault(s["metric"], []).append({"t": s["ts"], "v": s["value"], "local": s["local"]})
    return series


def day(store, date):
    rows = store.samples(date=date)
    activity = store.activity(date=date)
    all_nights = nights(store)
    night = next((n for n in all_nights if n["id"] == date), None)
    series = _series(rows)
    latest, stats = {}, {}
    for metric, points in series.items():
        values = [p["v"] for p in points]
        latest[metric] = points[-1]
        stats[metric] = {"min": min(values), "max": max(values), "avg": statistics.fmean(values), "n": len(values)}
    found = store.days()
    earlier = [d for d in found if d < date]
    later = [d for d in found if d > date]
    return {
        "date": date, "prev": earlier[-1] if earlier else None, "next": later[0] if later else None,
        "series": series, "latest": latest, "stats": stats,
        "activity": {"steps": sum(a["steps"] for a in activity), "distance_m": sum(a["distance_m"] for a in activity),
                     "kcal": sum(a["kcal"] for a in activity), "bins": activity},
        "derived": derived.day_metrics(rows, activity, night),
        "night": public_night(night) if night else None,
        "readiness": derived.readiness(all_nights, store.activity(), date),
        "workouts": [public_workout(store, w) for w in store.workouts(date=date)],
        "latest_night": all_nights[-1]["id"] if all_nights else None,
    }


def sleep(store, wanted=None):
    all_nights = nights(store)
    ids = [n["id"] for n in all_nights]
    wanted = wanted or (ids[-1] if ids else None)
    night = next((n for n in all_nights if n["id"] == wanted), None)
    if night is None:
        return {"night": None, "nights": ids}
    index = ids.index(wanted)
    return {
        "night": public_night(night), "nights": ids,
        "series": _series(store.samples(start_ts=night["start_ts"], end_ts=night["end_ts"])),
        "prev": ids[index - 1] if index else None, "next": ids[index + 1] if index + 1 < len(ids) else None,
        "consistency": derived.consistency(all_nights), "recovery": derived.recovery(all_nights),
    }


def trends(store, last_days=0, today=None):
    rows = derived.daily_rollup(store.samples(), store.activity(), nights(store))
    if last_days:
        cutoff = ((today or dt.date.today()) - dt.timedelta(days=last_days - 1)).isoformat()
        rows = [r for r in rows if r["date"] >= cutoff]
    return {"rows": rows}


def public_workout(store, w):
    code_kind = next((k for k, v in WORKOUTS.items() if v["code"] == w["ring_code"]), None)
    kind = w["kind"] or code_kind
    rows = store.samples(start_ts=w["start_ts"], end_ts=w["end_ts"] or w["start_ts"])
    return {**w, "label": WORKOUTS[kind]["label"] if kind else f"Workout (ring type {w['ring_code']})",
            "heart": [{"t": s["ts"], "v": s["value"], "local": s["local"]} for s in rows if s["metric"] == "heart_rate"]}


def workouts(store):
    return {"kinds": {k: v["label"] for k, v in WORKOUTS.items()},
            "workouts": [public_workout(store, w) for w in store.workouts()]}


def clock(store):
    local = lambda ts: dt.datetime.fromtimestamp(ts).isoformat(" ")
    return {"fixes": [{"id": f["id"], "status": f["status"], "offset": f["offset"],
                       "from_local": local(f["from_ts"]), "to_local": local(f["to_ts"]),
                       "moved_from_local": local(f["from_ts"] + f["offset"]), "moved_to_local": local(f["to_ts"] + f["offset"]),
                       "found_local": local(f["event_ts"])} for f in store.clock_fixes()]}
