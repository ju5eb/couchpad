# Couchpad

Android TV app where phones become game controllers. Scan the QR on the TV,
your phone's browser opens a SNES-style gamepad, and input travels over the
local Wi-Fi via WebSocket. No app install on the phone.

```
Phone (browser gamepad) ──WebSocket over LAN──► TV (Ktor server) ──► input
```

## Status

- **Phase 1 — controller transport.** QR, WebSocket, 2 players. Done.
- **Phase 2 — emulator (current).** Snes9x runs through
  [LibretroDroid](https://github.com/Swordfish90/LibretroDroid); each phone
  drives its own input port. Loads the first ROM found on the device.

## Emulator setup

The core is not committed. Fetch it (default ABI `armeabi-v7a`):

```bash
scripts/fetch-core.sh            # or: scripts/fetch-core.sh arm64-v8a
```

Upload games from the phone: tap **Games** on the controller page, then
**Upload a ROM**. The TV stores it, switches to it, and phones stay connected.
Any phone can pick another game from the same list.

ROMs live in `/sdcard/Android/data/dev.couchpad/files/roms/` (also reachable
with `adb push` once the app has run and created the folder).

### Frame pacing on weak TVs

LibretroDroid runs one emulated frame per drawn frame when the display reports
~60 Hz. A TV that can only draw ~40 fps then plays games at ~0.7x. On the first
game launch Couchpad measures drawn fps; below 55 it remembers the device needs
wall-clock pacing, restarts the game once, and from then on makes LibretroDroid
see a 50 Hz display (`RefreshRateContext`) so it runs up to two emulated frames
per draw. Check with `adb logcat -s Couchpad`.

For testing, `ControllerLatency.sfc` from
[PeterLemon/SNES](https://github.com/PeterLemon/SNES) flashes the screen white
while any button is held.

## Layout

| Path | Role |
| --- | --- |
| `domain/` | Pure Kotlin: buttons, wire protocol, player slot assignment (unit tested) |
| `server/` | Ktor CIO server: serves the gamepad page and the `/ws` endpoint |
| `ui/` | TV activity, QR rendering, per-player panels |
| `assets/controller.html` | Phone gamepad (multi-touch, auto-reconnect) |

## Protocol

| Direction | Message | Meaning |
| --- | --- | --- |
| Phone → TV | `d:<code>` / `u:<code>` | Button down / up |
| TV → Phone | `p:<n>` | You are player *n* |
| TV → Phone | `full` | No free slot |

HTTP endpoints (all require `?token=`):

| Method | Path | Purpose |
| --- | --- | --- |
| GET | `/games` | One name per line; current game prefixed with `*` |
| POST | `/games?name=<file>` | Raw ROM body, max 8 MB, `.sfc`/`.smc` only |
| POST | `/play?name=<file>` | Switch the TV to that game |

Codes: `U D L R A B X Y SL SR ST SE`.

The QR carries a per-launch token; connections without it are rejected.

## Build

Requires JDK 17+ and the Android SDK (API 34). Point `local.properties`
(git-ignored) at the SDK, e.g. `sdk.dir=E\:\\android-sdk`. Optionally set
`buildRoot=E\:/dev/build/couchpad` to keep build outputs out of a synced folder
like OneDrive, which locks files mid-build.

```bash
./gradlew testDebugUnitTest assembleDebug
```

Install on the TV with `adb install app/build/outputs/apk/debug/app-debug.apk`.
Phone and TV must be on the same network (guest/hotel Wi-Fi with client
isolation will block it).

## ROMs

None are included. Use homebrew or dumps of cartridges you own.
