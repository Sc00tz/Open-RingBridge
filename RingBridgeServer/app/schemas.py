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
    # Sensor type key as sent by the Android app. Actual values:
    #   hr, spo2, systolic, diastolic, steps, distance_m, calories,
    #   hrv, stress, resp_rate, blood_glucose, wearing_state, battery, temperature
    type:      str
    value:     float


class ReadingsBatch(BaseModel):
    readings: list[ReadingIn]


class ReadingOut(BaseModel):
    timestamp: int
    type:      str
    value:     float
    device_id: str


# ── Sleep ─────────────────────────────────────────────────────────────────────

class SleepSessionIn(BaseModel):
    start_ms:       int          # Unix milliseconds — session start
    end_ms:         int          # Unix milliseconds — session end
    total_sleep_ms: int = 0      # durations below are in milliseconds
    deep_sleep_ms:  int = 0
    light_sleep_ms: int = 0
    rem_sleep_ms:   int = 0
    nap_ms:         int = 0
    wake_ms:        int = 0


class SleepBatch(BaseModel):
    sessions: list[SleepSessionIn]


class SleepSessionOut(BaseModel):
    start_ms:       int
    end_ms:         int
    total_sleep_ms: int
    deep_sleep_ms:  int
    light_sleep_ms: int
    rem_sleep_ms:   int
    nap_ms:         int
    wake_ms:        int
    device_id:      str
