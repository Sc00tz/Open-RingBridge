"""
Forward ingested readings to InfluxDB and/or MQTT.
Called as a FastAPI background task — runs after the HTTP response is sent.
"""
import asyncio
import json
import logging
from typing import Any, List

import httpx

logger = logging.getLogger(__name__)

# Metadata for each sensor type used in HA discovery and InfluxDB tagging
SENSOR_META: dict[str, dict] = {
    "hr":        {"name": "Heart Rate",                  "unit": "bpm",   "icon": "mdi:heart-pulse"},
    "spo2":      {"name": "SpO₂",                        "unit": "%",     "icon": "mdi:blood-oxygen"},
    "systolic":  {"name": "Blood Pressure (Systolic)",   "unit": "mmHg",  "icon": "mdi:gauge"},
    "diastolic": {"name": "Blood Pressure (Diastolic)",  "unit": "mmHg",  "icon": "mdi:gauge"},
    "steps":     {"name": "Steps",                       "unit": "steps", "icon": "mdi:walk"},
    "stress":    {"name": "Stress",                      "unit": "",      "icon": "mdi:brain"},
    "hrv":       {"name": "HRV",                         "unit": "ms",    "icon": "mdi:heart-cog"},
    "resp_rate": {"name": "Respiratory Rate",            "unit": "brpm",  "icon": "mdi:lungs"},
    "battery":   {"name": "Battery",                     "unit": "%",     "icon": "mdi:battery",
                  "device_class": "battery", "state_class": "measurement"},
    "sleep_total": {"name": "Sleep (Total)",             "unit": "min",   "icon": "mdi:sleep"},
    "sleep_deep":  {"name": "Sleep (Deep)",              "unit": "min",   "icon": "mdi:sleep"},
    "sleep_light": {"name": "Sleep (Light)",             "unit": "min",   "icon": "mdi:sleep"},
    "sleep_rem":   {"name": "Sleep (REM)",               "unit": "min",   "icon": "mdi:sleep"},
    "sleep_nap":   {"name": "Sleep (Nap)",               "unit": "min",   "icon": "mdi:power-sleep"},
    "sleep_wake":  {"name": "Sleep (Awake)",             "unit": "min",   "icon": "mdi:sleep-off"},
}


# ── Entry point ───────────────────────────────────────────────────────────────

async def forward(settings: dict, readings: List[Any], device: Any) -> None:
    """Dispatch readings to enabled integrations. Exceptions are logged, not raised."""
    tasks = []

    if settings.get("influxdb_enabled") == "true" and settings.get("influxdb_url"):
        tasks.append(_write_influx(settings, readings, device))

    if settings.get("mqtt_enabled") == "true" and settings.get("mqtt_host"):
        tasks.append(asyncio.to_thread(_publish_mqtt, settings, readings, device))

    if tasks:
        results = await asyncio.gather(*tasks, return_exceptions=True)
        for r in results:
            if isinstance(r, Exception):
                logger.error("Forwarder error: %s", r)


# ── InfluxDB ──────────────────────────────────────────────────────────────────

async def _write_influx(settings: dict, readings: List[Any], device: Any) -> None:
    url    = settings["influxdb_url"].rstrip("/")
    token  = settings.get("influxdb_token", "")
    org    = settings.get("influxdb_org", "")
    bucket = settings.get("influxdb_bucket", "ringbridge")

    # Build InfluxDB line protocol (one line per reading, timestamp in ms)
    lines: list[str] = []
    device_name_escaped = device.name.replace(" ", "\\ ").replace(",", "\\,")
    device_id_escaped   = device.device_id.replace(" ", "\\ ").replace(",", "\\,")

    for r in readings:
        meta  = SENSOR_META.get(r.type, {})
        unit  = meta.get("unit", "").replace(" ", "\\ ")
        tags  = (
            f"device_id={device_id_escaped},"
            f"device_name={device_name_escaped},"
            f"type={r.type}"
        )
        if unit:
            tags += f",unit={unit}"
        # timestamp is Unix ms; InfluxDB precision=ms
        lines.append(f"ring_sensor,{tags} value={r.value} {r.timestamp}")

    body = "\n".join(lines)

    async with httpx.AsyncClient(timeout=10) as client:
        resp = await client.post(
            f"{url}/api/v2/write",
            params={"org": org, "bucket": bucket, "precision": "ms"},
            headers={"Authorization": f"Token {token}", "Content-Type": "text/plain; charset=utf-8"},
            content=body.encode(),
        )
        resp.raise_for_status()
        logger.info("InfluxDB: wrote %d points for %s (HTTP %s)", len(readings), device.device_id, resp.status_code)


# ── MQTT ──────────────────────────────────────────────────────────────────────

def _publish_mqtt(settings: dict, readings: List[Any], device: Any) -> None:
    """Synchronous — called via asyncio.to_thread."""
    import paho.mqtt.client as mqtt

    host   = settings["mqtt_host"]
    port   = int(settings.get("mqtt_port") or "1883")
    user   = settings.get("mqtt_username", "")
    pwd    = settings.get("mqtt_password", "")
    prefix = settings.get("mqtt_topic_prefix") or "ringbridge"

    client = mqtt.Client(mqtt.CallbackAPIVersion.VERSION1)
    if user:
        client.username_pw_set(user, pwd)

    client.connect(host, port, keepalive=10)
    client.loop_start()

    try:
        # Publish HA discovery + sensor value for each reading
        published_types: set[str] = set()
        for r in readings:
            if r.type not in published_types:
                _ha_discovery(client, prefix, device, r.type)
                published_types.add(r.type)

            topic = f"{prefix}/{device.device_id}/{r.type}"
            client.publish(topic, payload=str(r.value), qos=0, retain=True)

        logger.debug("MQTT: published %d readings for %s", len(readings), device.device_id)
    finally:
        client.loop_stop()
        client.disconnect()


def _ha_discovery(client, prefix: str, device: Any, sensor_type: str) -> None:
    """Publish a Home Assistant MQTT discovery message for this sensor."""
    meta       = SENSOR_META.get(sensor_type, {"name": sensor_type.replace("_", " ").title(), "unit": ""})
    unique_id  = f"ringbridge_{device.device_id}_{sensor_type}"
    state_topic = f"{prefix}/{device.device_id}/{sensor_type}"

    payload: dict = {
        "name":          meta["name"],
        "unique_id":     unique_id,
        "state_topic":   state_topic,
        "state_class":   meta.get("state_class", "measurement"),
        "icon":          meta.get("icon", "mdi:circle"),
        "device": {
            "identifiers": [f"ringbridge_{device.device_id}"],
            "name":        device.name,
            "model":       "Smart Ring",
            "manufacturer": "RingBridge",
        },
    }
    if meta.get("unit"):
        payload["unit_of_measurement"] = meta["unit"]
    if meta.get("device_class"):
        payload["device_class"] = meta["device_class"]

    discovery_topic = f"homeassistant/sensor/{unique_id}/config"
    client.publish(discovery_topic, payload=json.dumps(payload), qos=1, retain=True)
