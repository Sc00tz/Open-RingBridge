import uuid
from types import SimpleNamespace
from typing import Annotated

from fastapi import APIRouter, BackgroundTasks, Depends, HTTPException, Query, status
from sqlalchemy.orm import Session

from app.auth import get_current_device, get_registration_key
from app.database import get_db
from app.forwarder import forward
from app.models import Device, Reading
from app.routers.admin import get_setting
from app.schemas import (
    DeviceRegisterRequest,
    DeviceRegisterResponse,
    ReadingOut,
    ReadingsBatch,
)

router = APIRouter(prefix="/api/v1", tags=["api"])


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
    rows = [
        Reading(
            device_id=device.device_id,
            timestamp=r.timestamp,
            type=r.type,
            value=r.value,
        )
        for r in payload.readings
    ]

    # Snapshot plain values BEFORE commit — after commit SQLAlchemy expires all
    # ORM attributes, making them inaccessible from the async background task.
    fwd_readings = [
        SimpleNamespace(timestamp=r.timestamp, type=r.type, value=r.value)
        for r in rows
    ]
    fwd_device = SimpleNamespace(device_id=device.device_id, name=device.name)

    db.add_all(rows)
    db.commit()

    # Load forwarding settings and dispatch after response is sent
    settings = {
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
    background_tasks.add_task(forward, settings, fwd_readings, fwd_device)

    return {"inserted": len(rows), "device_id": device.device_id}


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
