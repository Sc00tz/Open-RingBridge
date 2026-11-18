"""User accounts, browser sessions and app tokens for the hub. Its own SQLite file, apart from health data.

Only hashes of passwords and tokens are stored. A token is shown once, when it's created.
"""
import hashlib
import hmac
import secrets
import sqlite3
import time

SCHEMA = """
CREATE TABLE IF NOT EXISTS user (
  id INTEGER PRIMARY KEY AUTOINCREMENT, username TEXT NOT NULL UNIQUE COLLATE NOCASE,
  password TEXT NOT NULL, is_admin INTEGER NOT NULL DEFAULT 0, created_ts INTEGER NOT NULL);
CREATE TABLE IF NOT EXISTS token (
  id INTEGER PRIMARY KEY AUTOINCREMENT, hash TEXT NOT NULL UNIQUE, user_id INTEGER NOT NULL REFERENCES user(id),
  kind TEXT NOT NULL, name TEXT NOT NULL, created_ts INTEGER NOT NULL, last_used_ts INTEGER, expires_ts INTEGER);
"""
SESSION_SECONDS = 30 * 86400
MIN_PASSWORD = 10
USERNAME_CHARS = set("abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789._-")
SCRYPT = {"n": 2 ** 14, "r": 8, "p": 1}


class AccountError(ValueError):
    pass


def hash_password(password, salt=None):
    salt = salt or secrets.token_bytes(16)
    digest = hashlib.scrypt(password.encode(), salt=salt, dklen=32, **SCRYPT)
    return f"scrypt${salt.hex()}${digest.hex()}"


def check_password(password, stored):
    try:
        _, salt, digest = stored.split("$")
    except ValueError:
        return False
    return hmac.compare_digest(hash_password(password, bytes.fromhex(salt)).split("$")[2], digest)


def _token_hash(token):
    return hashlib.sha256(token.encode()).hexdigest()


# Compared against when a username doesn't exist, so a wrong username takes as long as a wrong password.
_DUMMY = hash_password(secrets.token_hex(8))


class Accounts:
    def __init__(self, path):
        self.db = sqlite3.connect(path)
        self.db.row_factory = sqlite3.Row
        self.db.executescript(SCHEMA)

    def count(self):
        return self.db.execute("SELECT COUNT(*) FROM user").fetchone()[0]

    def create_user(self, username, password, is_admin=False):
        username = (username or "").strip()
        if not (1 <= len(username) <= 40) or not set(username) <= USERNAME_CHARS:
            raise AccountError("Usernames use letters, numbers, dots, dashes and underscores, up to 40 characters.")
        if len(password or "") < MIN_PASSWORD:
            raise AccountError(f"Passwords need at least {MIN_PASSWORD} characters.")
        try:
            cursor = self.db.execute("INSERT INTO user (username, password, is_admin, created_ts) VALUES (?,?,?,?)",
                                     (username, hash_password(password), int(is_admin), int(time.time())))
        except sqlite3.IntegrityError:
            raise AccountError("That username is taken.")
        self.db.commit()
        return self.user(cursor.lastrowid)

    def user(self, user_id):
        row = self.db.execute("SELECT id, username, is_admin, created_ts FROM user WHERE id=?", (user_id,)).fetchone()
        return dict(row) if row else None

    def users(self):
        return [dict(r) for r in self.db.execute("SELECT id, username, is_admin, created_ts FROM user ORDER BY id")]

    def verify(self, username, password):
        row = self.db.execute("SELECT * FROM user WHERE username=?", ((username or "").strip(),)).fetchone()
        ok = check_password(password or "", row["password"] if row else _DUMMY)
        return self.user(row["id"]) if row and ok else None

    def set_password(self, user_id, password, keep_token_id=None):
        """Changing a password signs out every other session and app."""
        if len(password or "") < MIN_PASSWORD:
            raise AccountError(f"Passwords need at least {MIN_PASSWORD} characters.")
        self.db.execute("UPDATE user SET password=? WHERE id=?", (hash_password(password), user_id))
        self.db.execute("DELETE FROM token WHERE user_id=? AND id IS NOT ?", (user_id, keep_token_id))
        self.db.commit()

    def issue(self, user_id, kind, name):
        """kind: "session" (browser, expires) or "app" (phone, until revoked). Returns the token, once."""
        token = secrets.token_urlsafe(32)
        now = int(time.time())
        self.db.execute("INSERT INTO token (hash, user_id, kind, name, created_ts, last_used_ts, expires_ts) VALUES (?,?,?,?,?,?,?)",
                        (_token_hash(token), user_id, kind, name[:80], now, now, now + SESSION_SECONDS if kind == "session" else None))
        self.db.commit()
        return token

    def authenticate(self, token, kind):
        if not token:
            return None
        row = self.db.execute("SELECT * FROM token WHERE hash=? AND kind=?", (_token_hash(token), kind)).fetchone()
        now = int(time.time())
        if row is None or (row["expires_ts"] and row["expires_ts"] < now):
            return None
        if now - (row["last_used_ts"] or 0) > 60:
            self.db.execute("UPDATE token SET last_used_ts=? WHERE id=?", (now, row["id"]))
            self.db.commit()
        user = self.user(row["user_id"])
        return {**user, "token_id": row["id"]} if user else None

    def revoke(self, user_id, token_id):
        self.db.execute("DELETE FROM token WHERE id=? AND user_id=?", (token_id, user_id))
        self.db.commit()

    def apps(self, user_id):
        return [dict(r) for r in self.db.execute(
            "SELECT id, name, created_ts, last_used_ts FROM token WHERE user_id=? AND kind='app' ORDER BY created_ts", (user_id,))]

    def prune(self):
        self.db.execute("DELETE FROM token WHERE expires_ts IS NOT NULL AND expires_ts < ?", (int(time.time()),))
        self.db.commit()
