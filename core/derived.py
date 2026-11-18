"""Metrics computed by combining ring data. Pure functions, so they port directly to Kotlin.

Nothing here comes from the ring or the official app. Each result says which formula and inputs
it used, so it can be checked and changed. These are wellness estimates, not medical readings.
"""
import datetime as dt
import statistics

NIGHT_GAP_SECONDS = 2 * 3600
DEFAULT_SLEEP_TARGET_SECONDS = 8 * 3600
BASELINE_NIGHTS = 5
READINESS_MIN_BASELINE = 3
MANY_STEPS_DURING_SLEEP = 300
# Score bands as the official app describes them. The score itself is our own formula.
BANDS = [(85, "Optimal"), (70, "Good"), (60, "Average"), (0, "Poor")]


def clamp(x, low=0.0, high=1.0):
    return max(low, min(high, x))


def group_nights(sessions):
    """The ring can split one night into several sessions, so join sessions under 2 hours apart.

    A night is identified by the date you woke up.
    """
    groups = []
    for s in sorted(sessions, key=lambda x: x["start_ts"]):
        if groups and s["start_ts"] - groups[-1][-1]["end_ts"] <= NIGHT_GAP_SECONDS:
            groups[-1].append(s)
        else:
            groups.append([s])
    nights = []
    for g in groups:
        seconds = {"deep": 0, "light": 0, "rem": 0, "awake": 0, "nap": 0}
        segments = []
        for i, s in enumerate(g):
            for seg in s["segments"]:
                if seg["stage"] in seconds:
                    seconds[seg["stage"]] += seg["seconds"]
                segments.append(seg)
            if i + 1 < len(g):  # show the gap between sessions as awake time on the timeline
                segments.append({"stage": "awake", "start_ts": s["end_ts"], "start_local": s["end_local"],
                                 "seconds": g[i + 1]["start_ts"] - s["end_ts"]})
        start, end = g[0]["start_ts"], g[-1]["end_ts"]
        in_bed = end - start
        asleep = seconds["deep"] + seconds["light"] + seconds["rem"] + seconds["nap"]
        # Gaps between sessions count as awake: the ring recorded no sleep during them.
        seconds["awake"] = max(seconds["awake"], in_bed - asleep)
        nights.append({"id": g[-1]["end_local"][:10], "start_ts": start, "end_ts": end,
                       "start_local": g[0]["start_local"], "end_local": g[-1]["end_local"],
                       "sessions": len(g), "in_bed": in_bed, "asleep": asleep, "seconds": seconds,
                       "efficiency": asleep / in_bed if in_bed else None, "segments": segments})
    return nights


def _percentile(values, fraction):
    ordered = sorted(values)
    return ordered[min(len(ordered) - 1, int(len(ordered) * fraction))]


def overnight_vitals(night, samples):
    """Summarize stored readings taken during a night. `samples` must be in time order."""
    by = {}
    for s in samples:
        if night["start_ts"] <= s["ts"] <= night["end_ts"]:
            by.setdefault(s["metric"], []).append(s)
    out = {"samples": {k: len(v) for k, v in by.items()}}
    hr = by.get("heart_rate", [])
    if len(hr) >= 6:
        values = [s["value"] for s in hr]
        out["hr_avg"] = statistics.fmean(values)
        out["hr_min"] = min(values)
        out["resting_hr"] = _percentile(values, 0.10)
        rolling = [(statistics.fmean(values[i:i + 3]), hr[i + 1]["ts"]) for i in range(len(values) - 2)]
        lowest = min(rolling, key=lambda r: r[0])
        out["hr_low_at_ts"] = lowest[1]
        out["hr_low_hours_before_wake"] = (night["end_ts"] - lowest[1]) / 3600
        out["hr_low_fraction"] = (lowest[1] - night["start_ts"]) / max(1, night["in_bed"])
    spo2 = [s["value"] for s in by.get("spo2", [])]
    if len(spo2) >= 6:
        out["spo2_avg"] = statistics.fmean(spo2)
        out["spo2_min"] = min(spo2)
        out["spo2_below_90_share"] = sum(v < 90 for v in spo2) / len(spo2)
    for metric, key in (("hrv", "hrv_avg"), ("respiration", "resp_avg")):
        values = [s["value"] for s in by.get(metric, [])]
        if len(values) >= 3:
            out[key] = statistics.fmean(values)
    return out


def hr_dip(night, samples):
    """How far heart rate fell overnight, against the waking 16 hours before bed."""
    window = night["start_ts"] - 16 * 3600
    day = [s["value"] for s in samples
           if s["metric"] == "heart_rate" and window <= s["ts"] < night["start_ts"]]
    asleep = [s["value"] for s in samples
              if s["metric"] == "heart_rate" and night["start_ts"] <= s["ts"] <= night["end_ts"]]
    if len(day) < 6 or len(asleep) < 6:
        return None
    return {"day_avg": statistics.fmean(day), "night_avg": statistics.fmean(asleep),
            "dip": 1 - statistics.fmean(asleep) / statistics.fmean(day)}


def band(score):
    return next(name for floor, name in BANDS if score >= floor)


def sleep_score(night, vitals, dip=None, hrv_median=None, target=DEFAULT_SLEEP_TARGET_SECONDS):
    """Our own 0-100 score. Missing inputs are left out and the rest rescaled, never guessed."""
    asleep = night["asleep"]
    restorative = (night["seconds"]["deep"] + night["seconds"]["rem"]) / asleep if asleep else 0
    factors = [
        ("Duration", 35, clamp(asleep / target), f"{asleep / 3600:.1f} h asleep of a {target / 3600:.0f} h target"),
        ("Deep and REM", 20, clamp(restorative / 0.40), f"{restorative:.0%} of sleep; full points at 40%"),
        ("Efficiency", 15, clamp(((night["efficiency"] or 0) - 0.70) / 0.20),
         f"{(night['efficiency'] or 0):.0%} of the night asleep; full points at 90%"),
    ]
    if dip:
        factors.append(("Heart rate dip", 10, clamp(dip["dip"] / 0.10), f"{dip['dip']:.0%} below daytime; full points at 10%"))
    if "spo2_avg" in vitals:
        factors.append(("Blood oxygen", 10, clamp((vitals["spo2_avg"] - 90) / 5), f"{vitals['spo2_avg']:.0f}% average; full points at 95%"))
    if "hrv_avg" in vitals and hrv_median:
        factors.append(("HRV", 10, clamp((vitals["hrv_avg"] / hrv_median - 0.7) / 0.3),
                        f"{vitals['hrv_avg']:.0f} against your usual {hrv_median:.0f}; full points at your usual"))
    possible = sum(f[1] for f in factors)
    score = round(100 * sum(f[1] * f[2] for f in factors) / possible)
    notes = []
    if asleep < 4 * 3600:
        notes.append("Under 4 hours recorded, so this may be a partial night.")
    if night["seconds"]["awake"] < 60:
        notes.append("The ring recorded no awake time, so efficiency may read high.")
    if possible < 100:
        notes.append(f"Based on {possible} of 100 possible points; the rest had too little data.")
    return {"score": score, "band": band(score), "confidence": "low" if notes else "ok", "notes": notes,
            "factors": [{"name": n, "weight": w, "earned": round(w * p, 1), "detail": d} for n, w, p, d in factors]}


def steps_during(night, activity):
    """Steps recorded during a night, prorated across partly overlapping activity periods.

    Real sleep has few. A high count means the ring called some waking time sleep.
    """
    total = 0
    for a in activity:
        overlap = min(a["end_ts"], night["end_ts"]) - max(a["start_ts"], night["start_ts"])
        if overlap > 0 and a["end_ts"] > a["start_ts"]:
            total += a["steps"] * overlap / (a["end_ts"] - a["start_ts"])
    return round(total)


def build_nights(sessions, samples, activity=(), target=DEFAULT_SLEEP_TARGET_SECONDS):
    nights = group_nights(sessions)
    for n in nights:
        n["vitals"] = overnight_vitals(n, samples)
        n["dip"] = hr_dip(n, samples)
        n["steps_during"] = steps_during(n, activity)
    hrvs = [n["vitals"]["hrv_avg"] for n in nights if "hrv_avg" in n["vitals"]]
    median = statistics.median(hrvs) if len(hrvs) >= 3 else None
    for n in nights:
        n["score"] = sleep_score(n, n["vitals"], n["dip"], median, target)
        if n["steps_during"] >= MANY_STEPS_DURING_SLEEP:
            n["score"]["confidence"] = "low"
            n["score"]["notes"].append(f"{n['steps_during']:,} steps were recorded during this session, so part of it may not be sleep.")
    return nights


def consistency(nights, target=DEFAULT_SLEEP_TARGET_SECONDS):
    """Bedtime and wake-time spread, and sleep debt, over the last 7 nights long enough to count."""
    full = [n for n in nights if n["asleep"] >= 4 * 3600][-7:]
    if len(full) < 3:
        return {"nights": len(full), "ready": False, "needed": 3}

    def clock(local):
        minutes = int(local[11:13]) * 60 + int(local[14:16])
        return minutes - 1440 if minutes > 720 else minutes  # keeps bedtimes either side of midnight together
    return {"nights": len(full), "ready": True,
            "bedtime_spread_min": statistics.pstdev(clock(n["start_local"]) for n in full),
            "wake_spread_min": statistics.pstdev(clock(n["end_local"]) for n in full),
            "avg_asleep": statistics.fmean(n["asleep"] for n in full),
            "debt_seconds": sum(max(0, target - n["asleep"]) for n in full)}


def recovery(nights):
    """Compare the latest night's resting heart rate and HRV with your own earlier nights."""
    usable = [n for n in nights if "resting_hr" in n["vitals"] and n["asleep"] >= 4 * 3600]
    if len(usable) <= BASELINE_NIGHTS:
        return {"ready": False, "have": max(0, len(usable) - 1), "needed": BASELINE_NIGHTS}
    latest, earlier = usable[-1], usable[-(BASELINE_NIGHTS + 1):-1]
    out = {"ready": True, "night": latest["id"], "resting_hr": latest["vitals"]["resting_hr"],
           "resting_hr_usual": statistics.median(n["vitals"]["resting_hr"] for n in earlier)}
    hrvs = [n["vitals"]["hrv_avg"] for n in earlier if "hrv_avg" in n["vitals"]]
    if hrvs and "hrv_avg" in latest["vitals"]:
        out["hrv"] = latest["vitals"]["hrv_avg"]
        out["hrv_usual"] = statistics.median(hrvs)
    return out


def _usual(nights, night, key):
    """Your median over up to 14 full earlier nights in the 30 days before this one. Needs 3 to count."""
    earlier = [n for n in nights if night["start_ts"] - 30 * 86400 <= n["start_ts"] < night["start_ts"]
               and n["asleep"] >= 4 * 3600 and key in n["vitals"]][-14:]
    return statistics.median(n["vitals"][key] for n in earlier) if len(earlier) >= READINESS_MIN_BASELINE else None


def _yesterday_factor(activity, date):
    """Yesterday's steps against your usual. Both far too few and far too many lower it, as the app's text describes."""
    by_day = {}
    for a in activity:
        day = a["start_local"][:10]
        by_day[day] = by_day.get(day, 0) + a["steps"]
    yesterday = (dt.date.fromisoformat(date) - dt.timedelta(days=1)).isoformat()
    window = (dt.date.fromisoformat(date) - dt.timedelta(days=15)).isoformat()
    earlier = [v for d, v in by_day.items() if window <= d < yesterday and v > 0]
    if not by_day.get(yesterday) or len(earlier) < READINESS_MIN_BASELINE:
        return None
    usual = statistics.median(earlier)
    ratio = by_day[yesterday] / usual
    points = clamp(ratio / 0.5) if ratio < 1 else clamp(1 - (ratio - 1.5) / 1.5)
    return ("Yesterday's activity", 10, points, f"{by_day[yesterday]:,} steps against your usual {usual:,.0f}; full points from half to 1.5 times usual")


def readiness(nights, activity, date, target=DEFAULT_SLEEP_TARGET_SECONDS):
    """Our own 0-100 daily score, set after waking. It uses the same factor names as the official app's
    Readiness, but its weights are our own, since the app's formula isn't known.

    Factors needing a personal baseline are left out until one exists, and the rest rescaled, never guessed.
    """
    night = next((n for n in nights if n["id"] == date), None)
    if night is None:
        return {"ready": False, "reason": "No sleep recorded for this date. Readiness is set once a night's sleep has synced."}
    vitals, score = night["vitals"], night["score"]
    factors = [("Last night's sleep", 35, score["score"] / 100, f"Sleep score {score['score']}")]
    week = [n for n in nights if night["start_ts"] - 7 * 86400 < n["start_ts"] <= night["start_ts"]]
    if len(week) >= READINESS_MIN_BASELINE:
        average = statistics.fmean(n["asleep"] for n in week)
        factors.append(("Sleep balance", 10, clamp(average / target),
                        f"{average / 3600:.1f} h average over {len(week)} recent nights, of a {target / 3600:.0f} h target"))
    usual_hr = _usual(nights, night, "resting_hr")
    if usual_hr and "resting_hr" in vitals:
        factors.append(("Resting heart rate", 20, clamp(1 - (vitals["resting_hr"] - usual_hr) / 10),
                        f"{vitals['resting_hr']:.0f} BPM against your usual {usual_hr:.0f}; no points at 10 BPM above"))
    usual_hrv = _usual(nights, night, "hrv_avg")
    if usual_hrv and "hrv_avg" in vitals:
        factors.append(("HRV balance", 15, clamp((vitals["hrv_avg"] / usual_hrv - 0.7) / 0.3),
                        f"{vitals['hrv_avg']:.0f} against your usual {usual_hrv:.0f}; full points at your usual"))
    if "hr_low_hours_before_wake" in vitals:
        hours = vitals["hr_low_hours_before_wake"]
        factors.append(("Recovery index", 10, clamp(hours / 6), f"Heart rate reached its low {hours:.1f} h before waking; full points at 6 h"))
    yesterday = _yesterday_factor(activity, date)
    if yesterday:
        factors.append(yesterday)
    possible = sum(f[1] for f in factors)
    value = round(100 * sum(f[1] * f[2] for f in factors) / possible)
    notes = []
    if possible < 100:
        notes.append(f"Based on {possible} of 100 possible points. The rest need more nights and days of data, so this will change as your baseline builds.")
    # Name the sleep score's own reasons, except its points note, which this card already covers.
    notes += [f"Sleep score: {n}" for n in score["notes"] if not n.startswith("Based on")]
    return {"ready": True, "night": night["id"], "score": value, "band": band(value), "possible": possible,
            "confidence": "low" if notes else "ok", "notes": notes,
            "factors": [{"name": n, "weight": w, "earned": round(w * p, 1), "detail": d} for n, w, p, d in factors]}


def workout_totals(first, last, heart):
    """Totals from the ring's live counters. `first` and `last` are (steps, distance_m, kcal), or None.

    The counters may be for the whole day, so only the change during the workout counts.
    """
    steps, distance, kcal = (max(0, b - a) for a, b in zip(first, last)) if first and last else (None, None, None)
    return {"steps": steps, "distance_m": distance, "kcal": kcal,
            "hr_avg": round(statistics.fmean(heart)) if heart else None,
            "hr_min": min(heart) if heart else None, "hr_max": max(heart) if heart else None}


def _item(id, name, value, unit, formula, inputs, caveat=None):
    return {"id": id, "name": name, "value": value, "unit": unit, "formula": formula, "inputs": inputs, "caveat": caveat}


def day_metrics(samples, activity, night=None):
    """Derived numbers for one day. An item only appears when its inputs exist."""
    items = []
    latest = {}
    for s in samples:
        latest.setdefault(s["metric"], []).append(s)
    sys_rows, dia_rows = latest.get("bp_systolic", []), {s["ts"]: s["value"] for s in latest.get("bp_diastolic", [])}
    pair = next(((s, dia_rows[s["ts"]]) for s in reversed(sys_rows) if s["ts"] in dia_rows), None)
    if pair:
        row, dia = pair
        estimate = "Uses the ring's blood pressure estimate."
        items.append(_item("pulse_pressure", "Pulse pressure", row["value"] - dia, "mmHg", "Systolic − diastolic",
                           f"{row['value']:.0f}/{dia:.0f} at {row['local'][11:16]}", estimate))
        items.append(_item("map", "Mean arterial pressure", round(dia + (row["value"] - dia) / 3), "mmHg",
                           "Diastolic + ⅓ × pulse pressure", f"{row['value']:.0f}/{dia:.0f} at {row['local'][11:16]}", estimate))
        hr = [s for s in latest.get("heart_rate", []) if abs(s["ts"] - row["ts"]) <= 180]
        if hr:
            nearest = min(hr, key=lambda s: abs(s["ts"] - row["ts"]))
            items.append(_item("rate_pressure", "Heart workload", round(row["value"] * nearest["value"]), "RPP",
                               "Heart rate × systolic", f"{nearest['value']:.0f} BPM × {row['value']:.0f} mmHg", estimate))
    steps = sum(a["steps"] for a in activity)
    distance = sum(a["distance_m"] for a in activity)
    if steps >= 100 and distance:
        items.append(_item("stride", "Average stride", round(distance / steps, 2), "m", "Distance ÷ steps",
                           f"{distance:,} m over {steps:,} steps", "The ring's distance is itself estimated from steps."))
    if activity:
        peak = max(activity, key=lambda a: a["steps"])
        minutes = max(1, (peak["end_ts"] - peak["start_ts"]) / 60)
        if peak["steps"]:
            items.append(_item("peak_cadence", "Busiest half hour", round(peak["steps"] / minutes), "steps/min",
                               "Steps ÷ minutes in the busiest period", f"{peak['steps']:,} steps from {peak['start_local'][11:16]}"))
    if night and night.get("dip"):
        dip = night["dip"]
        items.append(_item("hr_dip", "Overnight heart rate dip", round(dip["dip"] * 100), "%", "1 − night average ÷ day average",
                           f"{dip['night_avg']:.0f} BPM asleep, {dip['day_avg']:.0f} BPM in the 16 h before"))
    return items


def daily_rollup(samples, activity, nights):
    """One row per calendar day for the trend charts. A day only has the values it has data for."""
    days = {}
    for s in samples:
        days.setdefault(s["local"][:10], {}).setdefault(s["metric"], []).append(s["value"])
    rows = {d: {"date": d, **{m: statistics.fmean(v) for m, v in metrics.items()}} for d, metrics in days.items()}
    for a in activity:
        row = rows.setdefault(a["start_local"][:10], {"date": a["start_local"][:10]})
        row["steps"] = row.get("steps", 0) + a["steps"]
        row["distance_m"] = row.get("distance_m", 0) + a["distance_m"]
        row["kcal"] = row.get("kcal", 0) + a["kcal"]
    for n in nights:
        row = rows.setdefault(n["id"], {"date": n["id"]})
        row.update(asleep=n["asleep"], deep=n["seconds"]["deep"], light=n["seconds"]["light"], rem=n["seconds"]["rem"],
                   awake=n["seconds"]["awake"], sleep_score=n["score"]["score"], resting_hr=n["vitals"].get("resting_hr"),
                   readiness=readiness(nights, activity, n["id"])["score"])
    return [rows[d] for d in sorted(rows)]
