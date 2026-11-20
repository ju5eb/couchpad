# Couchpad

Android TV app where phones become game controllers. Scan the QR on the TV,
your phone's browser opens a SNES-style gamepad, and input travels over the
local Wi-Fi via WebSocket. No app install on the phone.

```
Phone (browser gamepad) ──WebSocket over LAN──► TV (Ktor server) ──► input
```

## Status

- **Phase 1 — controller transport.** QR, WebSocket, 2 players. Done.
- **Phase 2 — emulator.** Snes9x runs through
  [LibretroDroid](https://github.com/Swordfish90/LibretroDroid); each phone
  drives its own input port. Done.
- **Phase 3 — lobby (current).** The app opens on a lobby (QR, players with
  latency, game library). A phone's joystick or the TV remote picks a game.
  In game, the phone's ☰ menu pauses it and offers resume, back to lobby,
  restart and switching game.

## Emulator setup

The core is not committed. Fetch it (default ABI `armeabi-v7a`):

```bash
scripts/fetch-core.sh            # or: scripts/fetch-core.sh arm64-v8a
```

Upload games from the phone: tap **☰ Games** on the controller page, then
**Upload a ROM** (`.sfc`, `.smc`, or a `.zip` containing one). The TV stores it
and starts it; phones stay connected.

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
| `domain/` | Pure Kotlin: buttons, wire protocol, player slots, ROM library, lobby grid navigation (unit tested) |
| `server/` | Ktor CIO server: gamepad page, `/ws`, game and screen endpoints |
| `ControllerHub` | App-wide state: connected players, latency, pause, which screen is showing |
| `ui/LobbyActivity` | Launcher: QR, players, game library |
| `ui/GameActivity` | Emulator, pause overlay; Back returns to the lobby |
| `assets/controller.html` | Phone gamepad: joystick, face buttons, games / pause menu |

## Protocol

| Direction | Message | Meaning |
| --- | --- | --- |
| Phone → TV | `d:<code>` / `u:<code>` | Button down / up |
| Phone → TV | `t:<id>` | Ping, echoed back for round-trip time |
| Phone → TV | `r:<avg>:<max>` | Latency report (ms) |
| Phone → TV | `m:1` / `m:0` | Menu opened / closed: pauses / resumes the game |
| TV → Phone | `p:<n>` | You are player *n* |
| TV → Phone | `s:lobby` / `s:game` | Which screen the TV shows |
| TV → Phone | `full` | No free slot |

HTTP endpoints (all require `?token=`):

| Method | Path | Purpose |
| --- | --- | --- |
| GET | `/games` | One name per line; current game prefixed with `*` |
| POST | `/games?name=<file>` | Raw ROM body, max 8 MB, `.sfc`/`.smc`/`.zip` |
| POST | `/play?name=<file>` | Start or switch to that game |
| POST | `/lobby` | Leave the game, back to the lobby |
| POST | `/reset` | Restart the current game |
| POST | `/overlay` | Show / hide the QR panel over the game |

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
