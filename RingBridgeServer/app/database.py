import os
from sqlalchemy import create_engine
from sqlalchemy.orm import sessionmaker, Session
from app.models import Base

DATABASE_PATH = os.getenv("DATABASE_PATH", "/data/ringbridge.db")
DATABASE_URL  = f"sqlite:///{DATABASE_PATH}"

engine       = create_engine(DATABASE_URL, connect_args={"check_same_thread": False})
SessionLocal = sessionmaker(autocommit=False, autoflush=False, bind=engine)


def create_tables() -> None:
    Base.metadata.create_all(bind=engine)


def get_db():
    """FastAPI dependency — yields a database session then closes it."""
    db = SessionLocal()
    try:
        yield db
    finally:
        db.close()
