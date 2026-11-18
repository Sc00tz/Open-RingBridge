"""Generate tests/vectors.json: made-up records with hand-stated expected values, no personal data.

The Python tests use this file now, and the Kotlin port can use the same file later.
"""
import datetime as dt
import json
import pathlib
import struct

EPOCH = dt.datetime(2000, 1, 1)


def t(text):
    return int((dt.datetime.fromisoformat(text) - EPOCH).total_seconds())


T = "2026-03-14 01:30:00"
BLANK = 0xFFFFFFFF  # decodes to the year 2136: padding, must be dropped
vectors = {
    "frames": [
        {"code": 0x0200, "payload": "4743", "hex": "0200080047436fec"},
        {"code": 0x0201, "payload": "4746", "hex": "0201080047469b16"},
        {"code": 0x021B, "payload": "", "hex": "021b06009c70"},
        {"code": 0x0506, "payload": "", "hex": "050606008320"},
    ],
    "history": [
        {"name": "heart", "hex": (struct.pack("<IBB", t(T), 0, 62) + struct.pack("<IBB", t(T) + 300, 0, 0)
                                  + struct.pack("<IBB", BLANK, 0, 70)).hex(),
         "samples": [["heart_rate", T, 62]], "activity": [], "sleep": []},
        {"name": "blood", "hex": struct.pack("<IBBBB", t(T), 1, 119, 78, 0).hex(),
         "samples": [["bp_systolic", T, 119], ["bp_diastolic", T, 78]], "activity": [], "sleep": []},
        {"name": "steps", "hex": struct.pack("<IIHHH", t(T), t(T) + 1800, 362, 229, 14).hex(), "samples": [],
         "activity": [{"start": T, "end": "2026-03-14 02:00:00", "steps": 362, "distance_m": 229, "kcal": 14}], "sleep": []},
        {"name": "combined", "hex": (struct.pack("<IH", t(T), 9000)
                                     + bytes([60, 110, 70, 98, 14, 30, 4, 0, 15, 0, 0, 72, 0, 0])).hex(),
         "samples": [["heart_rate", T, 60], ["bp_systolic", T, 110], ["bp_diastolic", T, 70], ["spo2", T, 98],
                     ["respiration", T, 14], ["hrv", T, 30], ["cvrr", T, 4], ["glucose", T, 7.2]],
         "activity": [], "sleep": []},
        {"name": "body", "hex": (struct.pack("<I", t(T)) + bytes([4, 3, 9, 7, 4, 1, 1, 7, 2, 0])
                                 + struct.pack("<HBBHHHBxxx", 27, 0, 0, 0, 0, 0, 0)).hex(),
         "samples": [["ring_load", T, 4.3], ["ring_hrv_index", T, 9.7], ["stress", T, 4.1], ["ring_body", T, 1.7],
                     ["ring_sympathetic", T, 2.0], ["sdnn", T, 27]], "activity": [], "sleep": []},
        {"name": "sleep", "hex": (struct.pack("<HHIIHHHH", 0xAF00, 36, t(T), t(T) + 1800, 0xFFFF, 600, 600, 600)
                                  + bytes([0xF1]) + struct.pack("<I", t(T)) + (600).to_bytes(3, "little")
                                  + bytes([0xF3]) + struct.pack("<I", t(T) + 600) + (1200).to_bytes(3, "little")).hex(),
         "samples": [], "activity": [],
         "sleep": [{"start": T, "end": "2026-03-14 02:00:00", "segments": [
             {"stage": "deep", "start": T, "seconds": 600},
             {"stage": "rem", "start": "2026-03-14 01:40:00", "seconds": 1200}]}]},
        {"name": "workouts", "hex": struct.pack("<IIIHHBBBIBB", t(T), t(T) + 1800, 3000, 2100, 150, 1, 0, 128, 1800, 95, 160).ljust(42, b"\0").hex(),
         "samples": [], "activity": [], "sleep": [],
         "workouts": [{"start": T, "end": "2026-03-14 02:00:00", "steps": 3000, "distance_m": 2100, "kcal": 150, "ring_code": 1,
                       "start_method": 0, "hr_avg": 128, "seconds": 1800, "hr_min": 95, "hr_max": 160}]},
    ],
}
(pathlib.Path(__file__).parent / "vectors.json").write_text(json.dumps(vectors, indent=1))
