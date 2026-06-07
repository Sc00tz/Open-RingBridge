"""Unit tests for app.security — bcrypt hashing and legacy-password handling."""
from app.security import hash_password, is_hashed, verify_password


def test_hash_roundtrip():
    h = hash_password("hunter2")
    assert is_hashed(h)
    assert verify_password("hunter2", h)
    assert not verify_password("wrong", h)


def test_legacy_plaintext_fallback():
    # Pre-hashing deployments stored the password verbatim.
    assert verify_password("changeme", "changeme")
    assert not verify_password("nope", "changeme")
    assert not is_hashed("changeme")


def test_empty_stored_never_verifies():
    assert not verify_password("anything", "")


def test_long_password_does_not_raise():
    # bcrypt's 72-byte limit must be handled internally, not bubble up as ValueError.
    pw = "a" * 200
    h = hash_password(pw)
    assert verify_password(pw, h)


def test_distinct_salts_produce_distinct_hashes():
    assert hash_password("same") != hash_password("same")
