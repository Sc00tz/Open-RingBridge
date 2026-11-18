"""Importing one sync's raw history streams. Shared by the Mac bench (read over Bluetooth) and the hub
(uploaded by the phone), so both store and clock-check the same way.
"""
import pathlib

import codec
from store import to_ts


def import_sync(store, set_ts, streams, raw_folder=None):
    """streams: {name: (bytes, verified)}, where verified means the ring's end-packet checksum matched.

    `set_ts` is when the clock was set at the start of the sync. Raw bytes are always saved; a stream
    failing its checksum is not imported.
    """
    result = {"added": {}, "failed": []}
    newest_before = None
    if raw_folder is not None:
        pathlib.Path(raw_folder).mkdir(parents=True, exist_ok=True)
    for name in codec.HISTORY_ORDER:
        if name not in streams:
            continue
        data, verified = streams[name]
        if raw_folder is not None:
            (pathlib.Path(raw_folder) / f"{name}.bin").write_bytes(data)
        if not verified:
            result["failed"].append(name)
            continue
        decoded = codec.decode_history(name, data)
        # Labels as the ring stamped them, before any correction: what the clock check needs.
        labels = [t for t in (to_ts(local) for _, local, _ in decoded["samples"]) if t < set_ts]
        if labels:
            newest_before = max(newest_before or 0, max(labels))
        result["added"][name] = store.add_decoded(decoded)
    store.record_clock_set(set_ts, newest_before)
    result["newest_before_ts"] = newest_before
    result["clock_proposals"] = store.check_clock()
    store.log_sync("failed-checksum" if result["failed"] else "ok", result)
    return result
