from pydantic import BaseModel


# ── Device ────────────────────────────────────────────────────────────────────

class DeviceRegisterRequest(BaseModel):
    name:             str   # human-readable label, e.g. "Travis's Ring"
    registration_key: str   # must match server REGISTRATION_KEY setting


class DeviceRegisterResponse(BaseModel):
    device_id: str
    token:     str
    name:      str


# ── Readings ──────────────────────────────────────────────────────────────────

class ReadingIn(BaseModel):
    timestamp: int    # Unix milliseconds
    type:      str    # "heart_rate", "spo2", "bp_systolic", etc.
    value:     float


class ReadingsBatch(BaseModel):
    readings: list[ReadingIn]


class ReadingOut(BaseModel):
    timestamp: int
    type:      str
    value:     float
    device_id: str
