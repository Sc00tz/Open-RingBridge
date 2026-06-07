from sqlalchemy.orm import DeclarativeBase, Mapped, mapped_column
from sqlalchemy import BigInteger, Float, String, DateTime, UniqueConstraint
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
    __table_args__ = (
        # A reading is uniquely identified by device + time + type. App retries of
        # a not-yet-synced batch re-POST the same rows; this lets ingestion skip
        # the duplicates instead of accumulating them.
        UniqueConstraint("device_id", "timestamp", "type", name="uq_reading_dev_ts_type"),
    )

    id:        Mapped[int]   = mapped_column(primary_key=True)
    device_id: Mapped[str]   = mapped_column(String(50), index=True)
    timestamp: Mapped[int]   = mapped_column(BigInteger,  index=True)
    type:      Mapped[str]   = mapped_column(String(50),  index=True)
    value:     Mapped[float] = mapped_column(Float)


class SleepSession(Base):
    """One sleep session from a ring. All *_ms fields are durations in milliseconds;
    start_ms / end_ms are Unix-millisecond timestamps."""
    __tablename__ = "sleep_sessions"
    __table_args__ = (
        # A session is uniquely identified by its device + start time. Re-POSTs of
        # the same session (app retry) update in place rather than duplicating.
        UniqueConstraint("device_id", "start_ms", name="uq_sleep_device_start"),
    )

    id:             Mapped[int] = mapped_column(primary_key=True)
    device_id:      Mapped[str] = mapped_column(String(50), index=True)
    start_ms:       Mapped[int] = mapped_column(BigInteger, index=True)
    end_ms:         Mapped[int] = mapped_column(BigInteger)
    total_sleep_ms: Mapped[int] = mapped_column(BigInteger, default=0)
    deep_sleep_ms:  Mapped[int] = mapped_column(BigInteger, default=0)
    light_sleep_ms: Mapped[int] = mapped_column(BigInteger, default=0)
    rem_sleep_ms:   Mapped[int] = mapped_column(BigInteger, default=0)
    nap_ms:         Mapped[int] = mapped_column(BigInteger, default=0)
    wake_ms:        Mapped[int] = mapped_column(BigInteger, default=0)


class Setting(Base):
    """Key-value store for server configuration (InfluxDB, MQTT, etc.)."""
    __tablename__ = "settings"

    key:   Mapped[str] = mapped_column(String(100), primary_key=True)
    value: Mapped[str] = mapped_column(String(2000))
