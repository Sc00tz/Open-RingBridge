"""Password hashing helpers.

Admin passwords are stored as bcrypt hashes in the settings table. For backward
compatibility with deployments created before hashing was introduced, [verify_password]
also accepts a legacy plain-text match and signals (via [is_hashed]) that the stored
value should be re-hashed.

Uses the `bcrypt` library directly rather than passlib: passlib 1.7.x is incompatible
with bcrypt >= 4 (it probes a removed `bcrypt.__about__` attribute and crashes).
"""
import bcrypt

# bcrypt only hashes the first 72 bytes of input and raises on longer values, so we
# truncate explicitly. (72 bytes of password is far beyond any realistic admin secret.)
_MAX_BCRYPT_BYTES = 72


def _to_bytes(plain: str) -> bytes:
    return plain.encode("utf-8")[:_MAX_BCRYPT_BYTES]


def hash_password(plain: str) -> str:
    """Return a bcrypt hash of [plain]."""
    return bcrypt.hashpw(_to_bytes(plain), bcrypt.gensalt()).decode("utf-8")


def is_hashed(value: str) -> bool:
    """True if [value] looks like a bcrypt hash (vs. a legacy plain-text password)."""
    return value.startswith(("$2a$", "$2b$", "$2y$"))


def verify_password(plain: str, stored: str) -> bool:
    """
    Check [plain] against [stored].

    If [stored] is a bcrypt hash, verify normally. If it's a legacy plain-text
    value (pre-hashing deployment), fall back to a string compare so existing
    admins can still log in; the caller is expected to re-hash on success.
    """
    if not stored:
        return False
    if is_hashed(stored):
        try:
            return bcrypt.checkpw(_to_bytes(plain), stored.encode("utf-8"))
        except ValueError:
            return False
    # Legacy plain-text fallback.
    return plain == stored
