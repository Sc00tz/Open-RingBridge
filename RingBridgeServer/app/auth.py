import os
from fastapi import Depends, HTTPException, status
from fastapi.security import HTTPBearer, HTTPAuthorizationCredentials
from sqlalchemy.orm import Session
from app.database import get_db
from app.models import Device, Setting

_bearer = HTTPBearer()


def get_current_device(
    credentials: HTTPAuthorizationCredentials = Depends(_bearer),
    db: Session = Depends(get_db),
) -> Device:
    """Resolve a Bearer token to a Device row, or raise 401."""
    device = db.query(Device).filter(Device.token == credentials.credentials).first()
    if not device:
        raise HTTPException(
            status_code=status.HTTP_401_UNAUTHORIZED,
            detail="Invalid or expired token",
        )
    return device


def get_server_url(db: Session) -> str:
    """Return the server URL (DB setting takes priority over env var)."""
    setting = db.query(Setting).filter(Setting.key == "server_url").first()
    return setting.value if (setting and setting.value) else os.getenv("SERVER_URL", "")


def get_registration_key(db: Session) -> str:
    """Return the current registration key (DB setting takes priority over env var)."""
    setting = db.query(Setting).filter(Setting.key == "registration_key").first()
    return setting.value if setting else os.getenv("REGISTRATION_KEY", "changeme")


def get_admin_password(db: Session) -> str:
    """Return the current admin password hash (plain text stored for now; hashed in Phase 2)."""
    setting = db.query(Setting).filter(Setting.key == "admin_password").first()
    return setting.value if setting else os.getenv("ADMIN_PASSWORD", "changeme")
