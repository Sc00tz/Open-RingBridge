import base64
import io
import json
import uuid
from typing import Any

import httpx
import qrcode
from fastapi import APIRouter, Depends, Form, Request
from fastapi.responses import HTMLResponse, RedirectResponse
from fastapi.templating import Jinja2Templates
from sqlalchemy import func
from sqlalchemy.orm import Session

from app.auth import get_admin_password
from app.database import get_db
from app.models import Device, Reading, Setting
from app.security import hash_password, is_hashed, verify_password

router    = APIRouter(prefix="/admin")
templates = Jinja2Templates(directory="app/templates")

SETTING_KEYS = [
    "server_url", "registration_key", "admin_password",
    "influxdb_enabled", "influxdb_url", "influxdb_token", "influxdb_org", "influxdb_bucket",
    "mqtt_enabled", "mqtt_host", "mqtt_port", "mqtt_username", "mqtt_password", "mqtt_topic_prefix",
]


# ── Helpers ───────────────────────────────────────────────────────────────────

def is_authenticated(request: Request) -> bool:
    return bool(request.session.get("authenticated"))


def get_setting(db: Session, key: str, default: str = "") -> str:
    row = db.query(Setting).filter(Setting.key == key).first()
    return row.value if row else default


def save_setting(db: Session, key: str, value: str) -> None:
    row = db.query(Setting).filter(Setting.key == key).first()
    if row:
        row.value = value
    else:
        db.add(Setting(key=key, value=value))
    db.commit()


def make_qr_b64(data: dict) -> str:
    """Encode dict as JSON QR code, return base64 PNG."""
    qr = qrcode.QRCode(box_size=8, border=3)
    qr.add_data(json.dumps(data))
    qr.make(fit=True)
    img = qr.make_image(fill_color="black", back_color="white")
    buf = io.BytesIO()
    img.save(buf, format="PNG")
    return base64.b64encode(buf.getvalue()).decode()


def ctx(request: Request, **kwargs: Any) -> dict:
    """Base template context."""
    return {"request": request, **kwargs}


# ── Auth ──────────────────────────────────────────────────────────────────────

@router.get("/login", response_class=HTMLResponse)
def login_page(request: Request):
    if is_authenticated(request):
        return RedirectResponse("/admin/", status_code=302)
    return templates.TemplateResponse("login.html", ctx(request))


@router.post("/login", response_class=HTMLResponse)
def login(request: Request, password: str = Form(...), db: Session = Depends(get_db)):
    stored = get_admin_password(db)
    if verify_password(password, stored):
        # Transparently upgrade a legacy plain-text password to a bcrypt hash.
        if not is_hashed(stored):
            save_setting(db, "admin_password", hash_password(password))
        request.session["authenticated"] = True
        return RedirectResponse("/admin/", status_code=302)
    return templates.TemplateResponse("login.html", ctx(request, error="Incorrect password"))


@router.get("/logout")
def logout(request: Request):
    request.session.clear()
    return RedirectResponse("/admin/login", status_code=302)


# ── Dashboard ─────────────────────────────────────────────────────────────────

@router.get("/", response_class=HTMLResponse)
def dashboard(request: Request, db: Session = Depends(get_db)):
    if not is_authenticated(request):
        return RedirectResponse("/admin/login", status_code=302)

    devices = db.query(Device).order_by(Device.created_at.desc()).all()

    # Latest reading per device: {device_id: {type: (value, timestamp)}}
    latest: dict[str, dict] = {}
    for device in devices:
        subq = (
            db.query(Reading.type, func.max(Reading.timestamp).label("ts"))
            .filter(Reading.device_id == device.device_id)
            .group_by(Reading.type)
            .subquery()
        )
        rows = (
            db.query(Reading)
            .join(subq, (Reading.type == subq.c.type) & (Reading.timestamp == subq.c.ts))
            .filter(Reading.device_id == device.device_id)
            .all()
        )
        latest[device.device_id] = {r.type: r.value for r in rows}

    return templates.TemplateResponse(
        "dashboard.html", ctx(request, devices=devices, latest=latest)
    )


# ── Devices ───────────────────────────────────────────────────────────────────

@router.get("/devices", response_class=HTMLResponse)
def devices_page(request: Request, db: Session = Depends(get_db)):
    if not is_authenticated(request):
        return RedirectResponse("/admin/login", status_code=302)

    server_url = get_setting(db, "server_url", "http://localhost:8080")
    devices    = db.query(Device).order_by(Device.created_at.desc()).all()

    # Pre-generate QR codes for each device
    qr_codes = {
        d.device_id: make_qr_b64({
            "server": server_url,
            "token":  d.token,
            "device_id": d.device_id,
        })
        for d in devices
    }

    return templates.TemplateResponse(
        "devices.html", ctx(request, devices=devices, qr_codes=qr_codes)
    )


@router.post("/devices", response_class=HTMLResponse)
def add_device(request: Request, name: str = Form(...), db: Session = Depends(get_db)):
    if not is_authenticated(request):
        return RedirectResponse("/admin/login", status_code=302)

    device = Device(
        device_id=f"ring-{uuid.uuid4().hex[:8]}",
        name=name.strip(),
        token=str(uuid.uuid4()),
    )
    db.add(device)
    db.commit()
    return RedirectResponse("/admin/devices", status_code=302)


@router.post("/devices/{device_id}/delete")
def delete_device(device_id: str, request: Request, db: Session = Depends(get_db)):
    if not is_authenticated(request):
        return RedirectResponse("/admin/login", status_code=302)

    db.query(Device).filter(Device.device_id == device_id).delete()
    db.query(Reading).filter(Reading.device_id == device_id).delete()
    db.commit()
    return RedirectResponse("/admin/devices", status_code=302)


# ── Settings ──────────────────────────────────────────────────────────────────

@router.get("/settings", response_class=HTMLResponse)
def settings_page(request: Request, db: Session = Depends(get_db)):
    if not is_authenticated(request):
        return RedirectResponse("/admin/login", status_code=302)

    current = {k: get_setting(db, k) for k in SETTING_KEYS}
    # Defaults
    current.setdefault("mqtt_port",         current.get("mqtt_port") or "1883")
    current.setdefault("mqtt_topic_prefix", current.get("mqtt_topic_prefix") or "ringbridge")

    return templates.TemplateResponse("settings.html", ctx(request, s=current))


@router.post("/settings", response_class=HTMLResponse)
def save_settings(
    request: Request,
    db: Session = Depends(get_db),
    server_url:         str = Form(""),
    registration_key:   str = Form(""),
    new_password:       str = Form(""),
    influxdb_enabled:   str = Form(""),
    influxdb_url:       str = Form(""),
    influxdb_token:     str = Form(""),
    influxdb_org:       str = Form(""),
    influxdb_bucket:    str = Form(""),
    mqtt_enabled:       str = Form(""),
    mqtt_host:          str = Form(""),
    mqtt_port:          str = Form("1883"),
    mqtt_username:      str = Form(""),
    mqtt_password:      str = Form(""),
    mqtt_topic_prefix:  str = Form("ringbridge"),
):
    if not is_authenticated(request):
        return RedirectResponse("/admin/login", status_code=302)

    save_setting(db, "server_url",        server_url.strip())
    save_setting(db, "registration_key",  registration_key.strip())
    save_setting(db, "influxdb_enabled",  "true" if influxdb_enabled else "false")
    save_setting(db, "influxdb_url",      influxdb_url.strip())
    save_setting(db, "influxdb_token",    influxdb_token.strip())
    save_setting(db, "influxdb_org",      influxdb_org.strip())
    save_setting(db, "influxdb_bucket",   influxdb_bucket.strip())
    save_setting(db, "mqtt_enabled",      "true" if mqtt_enabled else "false")
    save_setting(db, "mqtt_host",         mqtt_host.strip())
    save_setting(db, "mqtt_port",         mqtt_port.strip() or "1883")
    save_setting(db, "mqtt_username",     mqtt_username.strip())
    save_setting(db, "mqtt_password",     mqtt_password.strip())
    save_setting(db, "mqtt_topic_prefix", mqtt_topic_prefix.strip() or "ringbridge")

    if new_password.strip():
        save_setting(db, "admin_password", hash_password(new_password.strip()))

    current = {k: get_setting(db, k) for k in SETTING_KEYS}
    return templates.TemplateResponse(
        "settings.html", ctx(request, s=current, saved=True)
    )


# ── Connection tests (called by htmx buttons) ─────────────────────────────────

@router.post("/settings/test/influx", response_class=HTMLResponse)
async def test_influx(request: Request, db: Session = Depends(get_db)):
    if not is_authenticated(request):
        return HTMLResponse('<span class="text-red-600">Not authenticated</span>')

    url   = get_setting(db, "influxdb_url")
    token = get_setting(db, "influxdb_token")

    if not url:
        return HTMLResponse('<span class="text-red-600">InfluxDB URL not set</span>')

    try:
        async with httpx.AsyncClient(timeout=5) as client:
            r = await client.get(
                f"{url.rstrip('/')}/health",
                headers={"Authorization": f"Token {token}"},
            )
        if r.status_code == 200:
            return HTMLResponse('<span class="text-green-600 font-medium">Connected ✓</span>')
        return HTMLResponse(f'<span class="text-red-600">HTTP {r.status_code}</span>')
    except Exception as e:
        return HTMLResponse(f'<span class="text-red-600">Error: {e}</span>')


@router.post("/settings/test/mqtt", response_class=HTMLResponse)
def test_mqtt(request: Request, db: Session = Depends(get_db)):
    if not is_authenticated(request):
        return HTMLResponse('<span class="text-red-600">Not authenticated</span>')

    host = get_setting(db, "mqtt_host")
    port = int(get_setting(db, "mqtt_port") or "1883")
    user = get_setting(db, "mqtt_username")
    pwd  = get_setting(db, "mqtt_password")

    if not host:
        return HTMLResponse('<span class="text-red-600">MQTT host not set</span>')

    try:
        import paho.mqtt.client as mqtt
        client = mqtt.Client(mqtt.CallbackAPIVersion.VERSION1)
        if user:
            client.username_pw_set(user, pwd)
        client.connect(host, port, keepalive=5)
        client.disconnect()
        return HTMLResponse('<span class="text-green-600 font-medium">Connected ✓</span>')
    except Exception as e:
        return HTMLResponse(f'<span class="text-red-600">Error: {e}</span>')
