from sqlalchemy.orm import DeclarativeBase, Mapped, mapped_column
from sqlalchemy import BigInteger, Float, String, DateTime
from datetime import datetime, timezone


class Base(DeclarativeBase):
    pass


class Device(Base):
    """A registered ring / phone combination with its API token."""
    __tablename__ = "devices"

    id:         Mapped[int]      = mapped_column(primary_key=True)
    device_id:  Mapped[str]      = mapped_column(String(50),   unique=True, index=True)
    name:       Mapped[str]      = mapped_column(String(100))
    token:      Mapped[str]      = mapped_column(String(36),   unique=True, index=True)
    created_at: Mapped[datetime] = mapped_column(
        DateTime, default=lambda: datetime.now(timezone.utc)
    )


class Reading(Base):
    """One sensor reading from a ring.  timestamp is Unix milliseconds."""
    __tablename__ = "readings"

    id:        Mapped[int]   = mapped_column(primary_key=True)
    device_id: Mapped[str]   = mapped_column(String(50), index=True)
    timestamp: Mapped[int]   = mapped_column(BigInteger,  index=True)
    type:      Mapped[str]   = mapped_column(String(50),  index=True)
    value:     Mapped[float] = mapped_column(Float)


class Setting(Base):
    """Key-value store for server configuration (InfluxDB, MQTT, etc.)."""
    __tablename__ = "settings"

    key:   Mapped[str] = mapped_column(String(100), primary_key=True)
    value: Mapped[str] = mapped_column(String(2000))
