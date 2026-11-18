# Mac bench

A local dashboard that talks to the ring over the Mac's Bluetooth. It's for testing protocol changes
before they go into the Android app. It only listens on this Mac, at http://127.0.0.1:8787.

```sh
python3 -m venv .venv && .venv/bin/pip install -r bench/requirements.txt   # once, from the repo root
.venv/bin/python bench/server.py
```

Your data stays in `bench/data/` (never committed): `ring.db` holds the imported readings, and `raw/`
holds the raw bytes of every sync. Close the official app and the Android app first, because the ring
appears to allow one connection at a time.

`RING_NAME` sets which ring to look for, for example `RING_NAME="R01L C923"`.

Things this can't test: background syncing, Android permissions, and whether the ring behaves the same
over Android's Bluetooth stack.
