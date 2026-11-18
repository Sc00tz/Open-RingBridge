"""Finding and fixing readings the ring stamped with a wrong clock. Pure functions; ports to Kotlin as-is.

The ring stamps every record with its own clock and has no command to read that clock back
(0x020D and 0x0208 both answer FC). When its battery runs flat the clock stops, and after charging
it carries on from where it stopped, so everything it records is late by however long it was dead.
Each sync sets the clock, which ends the problem but doesn't fix records already stored.

What gives it away: right after a sync sets the clock, the ring keeps recording every ~5 minutes,
but the records just before the sync end long ago. A ring that was simply off the finger looks the
same if it was put back on just before syncing, so a found fix is only a proposal until confirmed.
"""
import statistics

MIN_LAG_SECONDS = 30 * 60       # smaller gaps are ordinary off-finger time
FOLLOW_SECONDS = 7 * 60         # a record this soon after the clock was set means the ring was being worn
CHARGE_GAP_SECONDS = 45 * 60    # a dead ring has to charge before it records again
BIN_SECONDS = 30 * 60           # step periods cover half an hour
DEFAULT_CADENCE = 300


def coverage(sample_ts, bin_starts):
    """Sorted (start, end) spans the ring was recording: each sample is a point, each step period half an hour."""
    spans = [(t, t) for t in sample_ts] + [(t, t + BIN_SECONDS) for t in bin_starts]
    return sorted(spans)


def longest_gap(spans, low, high):
    """The longest stretch with nothing recorded, between low and high. Returns (gap_start, gap_end) or None."""
    best, reach = None, None
    for start, end in spans:
        if end <= low or start > high:
            continue
        if reach is not None and start - reach >= CHARGE_GAP_SECONDS and (best is None or start - reach > best[1] - best[0]):
            best = (reach, start)
        reach = end if reach is None else max(reach, end)
    return best


def cadence(sample_ts, before):
    """Usual spacing of the ring's automatic records in the two hours up to `before`."""
    recent = sorted(t for t in sample_ts if before - 7200 <= t <= before)
    gaps = [b - a for a, b in zip(recent, recent[1:]) if 0 < b - a <= 600]
    return statistics.median(gaps) if len(gaps) >= 3 else DEFAULT_CADENCE


def detect(event, previous_set_ts, sample_ts, bin_starts):
    """Check one clock setting. `event` is {"set_ts", "newest_before"}: when a sync set the clock and the
    newest record label before that. `previous_set_ts` is the sync before it, when the clock was last right.

    Returns a proposed fix {"from_ts", "to_ts", "offset", "basis"} or None. Records labelled from_ts..to_ts
    (inclusive) belong `offset` seconds later.
    """
    set_ts, newest = event["set_ts"], event.get("newest_before")
    if newest is None or previous_set_ts is None or newest <= previous_set_ts:
        return None
    if set_ts - newest < MIN_LAG_SECONDS:
        return None
    follow = [t for t in sample_ts if set_ts <= t <= set_ts + FOLLOW_SECONDS]
    if not follow:
        return None
    first_after = min(follow)
    # The record after `newest` came one cadence later in real time, and the ring's clock can't be ahead of now.
    offset = min(first_after - newest - cadence(sample_ts, newest), set_ts - newest)
    offset = int(round(offset / 60) * 60)
    if offset < MIN_LAG_SECONDS or offset >= set_ts - previous_set_ts:
        return None
    gap = longest_gap(coverage(sample_ts, bin_starts), previous_set_ts, newest)
    from_ts = (gap[0] if gap else previous_set_ts) + 1  # the last record before the gap was stamped correctly
    return {"from_ts": int(from_ts), "to_ts": int(newest), "offset": offset,
            "basis": {"set_ts": set_ts, "newest_before": newest, "first_after": first_after,
                      "previous_set_ts": previous_set_ts, "charge_gap": gap}}


def in_fix(ts, fixes):
    for f in fixes:
        if f["from_ts"] <= ts <= f["to_ts"]:
            return f["offset"]
    return 0


def shift_decoded(decoded, fixes, to_ts, to_local):
    """Move decoded records whose ring label falls inside an applied fix. Sleep moves as whole sessions."""
    if not fixes:
        return decoded

    def move(local):
        ts = to_ts(local)
        offset = in_fix(ts, fixes)
        return to_local(ts + offset) if offset else local

    out = dict(decoded)
    out["samples"] = [(m, move(local), v) for m, local, v in decoded["samples"]]
    out["activity"] = []
    for a in decoded["activity"]:
        offset = in_fix(to_ts(a["start"]), fixes)
        out["activity"].append({**a, "start": to_local(to_ts(a["start"]) + offset), "end": to_local(to_ts(a["end"]) + offset)}
                               if offset else a)
    out["sleep"] = []
    for s in decoded["sleep"]:
        offset = in_fix(to_ts(s["start"]), fixes)
        if not offset:
            out["sleep"].append(s)
            continue
        shift = lambda local: to_local(to_ts(local) + offset)
        out["sleep"].append({**s, "start": shift(s["start"]), "end": shift(s["end"]),
                             "segments": [{**g, "start": shift(g["start"])} for g in s["segments"]]})
    out["workouts"] = []
    for w in decoded.get("workouts", []):
        offset = in_fix(to_ts(w["start"]), fixes)
        out["workouts"].append({**w, "start": to_local(to_ts(w["start"]) + offset), "end": to_local(to_ts(w["end"]) + offset)}
                               if offset else w)
    return out
