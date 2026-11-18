# Open RingBridge

Your smart ring's data, without the vendor's app or cloud. Open RingBridge talks to Yucheng-based rings
(sold as "Health Ring", model R01L) directly over Bluetooth. You get heart rate, blood oxygen, HRV,
stress, sleep stages, steps and workouts, plus a daily Readiness score.

It has three parts:

| Part | What it does |
|---|---|
| **Android app** (`android/`) | Reads the ring, stores everything on the phone, and works with no server. Download the APK from [Releases](../../releases). |
| **Hub** (`hub/`, Docker) | Optional self-hosted server. The app uploads to it, and it serves the same dashboard in a browser for every account. |
| **Mac bench** (`bench/`) | A local dashboard that talks to the ring over the Mac's Bluetooth, for protocol testing. |

The ring protocol is documented in [PROTOCOL.md](PROTOCOL.md), with each claim marked as observed on a
real ring, read from the vendor's code, or inferred.

> Wellness estimates, not medical readings. Blood pressure and glucose especially are the ring's own
> estimates and can be far off. Not affiliated with Yucheng or the makers of the Health Ring app.

## Run the hub

```sh
curl -O https://raw.githubusercontent.com/Sc00tz/Open-RingBridge/main/compose.yaml
# edit TZ in compose.yaml to your time zone, then:
docker compose up -d
```

Open `http://<server>:8080` and create the first account, which becomes the admin. Then, in the
Android app, go to **Ring > Hub** and sign in with the hub's address.

- **Time zone:** set `TZ` to where you live, because the ring records wall-clock time.
- **HTTPS:** put the hub behind a reverse proxy (Caddy, Traefik or nginx) before exposing it beyond
  your network, and set `HUB_TRUST_PROXY=1`.
- **Data:** everything lives in the `ringbridge-data` volume. Each account has its own database.
- **Locked out:** `docker compose exec hub python hub/manage.py passwd <name>` resets a password.

## How the pieces fit

```
ring ──Bluetooth──► Android app ──(optional) HTTPS──► hub ──► browser dashboard
                    stores and shows                  stores per account
                    everything itself
```

The phone uploads the ring's **raw history bytes** with their checksums. The hub checks the checksums
again and decodes them itself, so it never has to trust the app's parsing. The Python decoder in `core/`
and the Kotlin one in the app are both checked against `tests/vectors.json`.

| Python (`core/`) | Kotlin (`android/`) |
|---|---|
| `codec.py`: framing, CRC, history records | `ring/Codec.kt` |
| `derived.py`: nights, sleep score, Readiness, combined metrics | `data/Derived.kt` |
| `clockfix.py`: readings stamped by a late ring clock | `data/ClockFix.kt` |
| `store.py`: SQLite tables | `data/Store.kt`, same tables and columns |
| `views.py`: screen data | the screens' ViewModels |

## Rules the code follows

- **No destructive commands.** It never sends the history acknowledgement (`0x0580`), or any delete,
  reset or firmware command.
- **Bad data isn't imported.** A stream failing its checksum is backed up raw but not imported, and
  zero means "not measured".
- **Late clocks are fixed only with your OK.** If the ring's clock was behind (after a flat battery), sync
  finds it and asks before moving those readings. The move can be undone.
- **Sync at least daily.** The ring only keeps about a day of heart and vitals records. The Android app
  syncs in the background.
- **Derived numbers show their working.** Readiness and the sleep score are this project's own formulas,
  not the ring's or the official app's.

## Development

```sh
python3 -m venv .venv && .venv/bin/pip install -r bench/requirements.txt
.venv/bin/python -m unittest discover -s tests          # Python tests
(cd android && ./gradlew testDebugUnitTest assembleDebug) # Android tests and APK
docker build -t open-ringbridge-hub .                    # hub image
```

Pushing a tag like `v0.1.0` builds the APK and the hub image and publishes a release. The hub image goes
to `ghcr.io/sc00tz/open-ringbridge-hub`.
