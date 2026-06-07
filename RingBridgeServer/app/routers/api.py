import uuid
from types import SimpleNamespace
from typing import Annotated

from fastapi import APIRouter, BackgroundTasks, Depends, HTTPException, Query, status
from sqlalchemy.dialects.sqlite import insert as sqlite_insert
from sqlalchemy.orm import Session

from app.auth import get_current_device, get_registration_key
from app.database import get_db
from app.forwarder import forward
from app.models import Device, Reading, SleepSession
from app.routers.admin import get_setting
from app.schemas import (
    DeviceRegisterRequest,
    DeviceRegisterResponse,
    ReadingOut,
    ReadingsBatch,
    SleepBatch,
    SleepSessionOut,
)

router = APIRouter(prefix="/api/v1", tags=["api"])


def _forward_settings(db: Session) -> dict:
    """Load the InfluxDB / MQTT forwarding configuration from the settings store."""
    return {
        "influxdb_enabled":  get_setting(db, "influxdb_enabled"),
        "influxdb_url":      get_setting(db, "influxdb_url"),
        "influxdb_token":    get_setting(db, "influxdb_token"),
        "influxdb_org":      get_setting(db, "influxdb_org"),
        "influxdb_bucket":   get_setting(db, "influxdb_bucket"),
        "mqtt_enabled":      get_setting(db, "mqtt_enabled"),
        "mqtt_host":         get_setting(db, "mqtt_host"),
        "mqtt_port":         get_setting(db, "mqtt_port") or "1883",
        "mqtt_username":     get_setting(db, "mqtt_username"),
        "mqtt_password":     get_setting(db, "mqtt_password"),
        "mqtt_topic_prefix": get_setting(db, "mqtt_topic_prefix") or "ringbridge",
    }


# ── Device registration ───────────────────────────────────────────────────────

@router.post("/devices/register", response_model=DeviceRegisterResponse, status_code=201)
def register_device(
    payload: DeviceRegisterRequest,
    db: Session = Depends(get_db),
):
    """
    Register a new ring/phone. Requires the server's registration key.
    Returns a device_id and Bearer token — store these in the Android app settings.
    """
    if payload.registration_key != get_registration_key(db):
        raise HTTPException(status_code=status.HTTP_403_FORBIDDEN, detail="Invalid registration key")

    device = Device(
        device_id=f"ring-{uuid.uuid4().hex[:8]}",
        name=payload.name,
        token=str(uuid.uuid4()),
    )
    db.add(device)
    db.commit()
    db.refresh(device)

    return DeviceRegisterResponse(
        device_id=device.device_id,
        token=device.token,
        name=device.name,
    )


# ── Data ingestion ────────────────────────────────────────────────────────────

@router.post("/readings", status_code=201)
def ingest_readings(
    payload: ReadingsBatch,
    background_tasks: BackgroundTasks,
    device: Device = Depends(get_current_device),
    db: Session = Depends(get_db),
):
    """
    Accept a batch of sensor readings from the Android app.
    The app buffers locally and POSTs when online.
    """
    values = [
        {
            "device_id": device.device_id,
            "timestamp": r.timestamp,
            "type":      r.type,
            "value":     r.value,
        }
        for r in payload.readings
    ]

    # Snapshot for the forwarder before any DB work (so it's independent of ORM state).
    fwd_readings = [
        SimpleNamespace(timestamp=v["timestamp"], type=v["type"], value=v["value"])
        for v in values
    ]
    fwd_device = SimpleNamespace(device_id=device.device_id, name=device.name)

    # ON CONFLICT DO NOTHING: app retries of a not-yet-synced batch re-POST the same
    # rows; the (device_id, timestamp, type) unique constraint makes those no-ops
    # instead of duplicate inserts.
    if values:
        db.execute(sqlite_insert(Reading).on_conflict_do_nothing().values(values))
        db.commit()

    # Load forwarding settings and dispatch after response is sent
    background_tasks.add_task(forward, _forward_settings(db), fwd_readings, fwd_device)

    return {"received": len(values), "device_id": device.device_id}


# ── Sleep ingestion ───────────────────────────────────────────────────────────

@router.post("/sleep", status_code=201)
def ingest_sleep(
    payload: SleepBatch,
    background_tasks: BackgroundTasks,
    device: Device = Depends(get_current_device),
    db: Session = Depends(get_db),
):
    """
    Accept a batch of sleep sessions from the Android app.

    Sessions are upserted on (device_id, start_ms): re-POSTing the same session
    (e.g. after a failed sync) updates it in place rather than creating a duplicate.
    Per-stage durations are also forwarded to InfluxDB/MQTT as ordinary readings
    (timestamped at session start) so they show up alongside the other metrics.
    """
    upserted = 0
    fwd_readings = []
    for s in payload.sessions:
        existing = (
            db.query(SleepSession)
            .filter(SleepSession.device_id == device.device_id,
                    SleepSession.start_ms == s.start_ms)
            .first()
        )
        if existing:
            existing.end_ms         = s.end_ms
            existing.total_sleep_ms = s.total_sleep_ms
            existing.deep_sleep_ms  = s.deep_sleep_ms
            existing.light_sleep_ms = s.light_sleep_ms
            existing.rem_sleep_ms   = s.rem_sleep_ms
            existing.nap_ms         = s.nap_ms
            existing.wake_ms        = s.wake_ms
        else:
            db.add(SleepSession(device_id=device.device_id, **s.model_dump()))
        upserted += 1

        # Surface sleep durations (in minutes) as forwardable readings.
        for sensor_type, ms in (
            ("sleep_total", s.total_sleep_ms),
            ("sleep_deep",  s.deep_sleep_ms),
            ("sleep_light", s.light_sleep_ms),
            ("sleep_rem",   s.rem_sleep_ms),
            ("sleep_nap",   s.nap_ms),
            ("sleep_wake",  s.wake_ms),
        ):
            fwd_readings.append(
                SimpleNamespace(timestamp=s.start_ms, type=sensor_type, value=ms / 60_000.0)
            )

    fwd_device = SimpleNamespace(device_id=device.device_id, name=device.name)
    db.commit()

    background_tasks.add_task(forward, _forward_settings(db), fwd_readings, fwd_device)

    return {"upserted": upserted, "device_id": device.device_id}


# ── Data query ────────────────────────────────────────────────────────────────

@router.get("/readings", response_model=list[ReadingOut])
def get_readings(
    device: Device = Depends(get_current_device),
    db: Session = Depends(get_db),
    type:  Annotated[str | None,  Query(description="Sensor type filter")] = None,
    start: Annotated[int | None,  Query(description="Start timestamp (Unix ms)")] = None,
    end:   Annotated[int | None,  Query(description="End timestamp (Unix ms)")] = None,
    limit: Annotated[int,         Query(le=10_000)] = 1_000,
):
    """Query stored readings for this device."""
    q = db.query(Reading).filter(Reading.device_id == device.device_id)

    if type:  q = q.filter(Reading.type == type)
    if start: q = q.filter(Reading.timestamp >= start)
    if end:   q = q.filter(Reading.timestamp <= end)

    rows = q.order_by(Reading.timestamp.desc()).limit(limit).all()

    return [
        ReadingOut(timestamp=r.timestamp, type=r.type, value=r.value, device_id=r.device_id)
        for r in rows
    ]


# ── Latest readings (one per type) ───────────────────────────────────────────

@router.get("/readings/latest", response_model=list[ReadingOut])
def get_latest_readings(
    device: Device = Depends(get_current_device),
    db: Session = Depends(get_db),
):
    """Return the most recent reading of each sensor type for this device."""
    from sqlalchemy import func

    subq = (
        db.query(Reading.type, func.max(Reading.timestamp).label("max_ts"))
        .filter(Reading.device_id == device.device_id)
        .group_by(Reading.type)
        .subquery()
    )

    rows = (
        db.query(Reading)
        .join(subq, (Reading.type == subq.c.type) & (Reading.timestamp == subq.c.max_ts))
        .filter(Reading.device_id == device.device_id)
        .all()
    )

    return [
        ReadingOut(timestamp=r.timestamp, type=r.type, value=r.value, device_id=r.device_id)
        for r in rows
    ]


# ── Sleep query ───────────────────────────────────────────────────────────────

@router.get("/sleep", response_model=list[SleepSessionOut])
def get_sleep(
    device: Device = Depends(get_current_device),
    db: Session = Depends(get_db),
    start: Annotated[int | None, Query(description="Earliest session start (Unix ms)")] = None,
    end:   Annotated[int | None, Query(description="Latest session start (Unix ms)")] = None,
    limit: Annotated[int,        Query(le=1_000)] = 100,
):
    """Query stored sleep sessions for this device, most recent first."""
    q = db.query(SleepSession).filter(SleepSession.device_id == device.device_id)

    if start: q = q.filter(SleepSession.start_ms >= start)
    if end:   q = q.filter(SleepSession.start_ms <= end)

    rows = q.order_by(SleepSession.start_ms.desc()).limit(limit).all()

    return [
        SleepSessionOut(
            start_ms=r.start_ms,
            end_ms=r.end_ms,
            total_sleep_ms=r.total_sleep_ms,
            deep_sleep_ms=r.deep_sleep_ms,
            light_sleep_ms=r.light_sleep_ms,
            rem_sleep_ms=r.rem_sleep_ms,
            nap_ms=r.nap_ms,
            wake_ms=r.wake_ms,
            device_id=r.device_id,
        )
        for r in rows
    ]
