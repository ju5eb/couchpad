# Couchpad

Android TV app where phones become game controllers. Scan the QR on the TV,
your phone's browser opens a SNES-style gamepad, and input travels over the
local Wi-Fi via WebSocket. No app install on the phone.

```
Phone (browser gamepad) ──WebSocket over LAN──► TV (Ktor server) ──► input
```

## Status

**Phase 1 — controller transport (current).** The TV shows a QR and live
button state for 2 players. No emulator yet.

Next: embed a libretro SNES core (Snes9x) via LibretroDroid and route
controller events to its input ports.

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

Codes: `U D L R A B X Y SL SR ST SE`.

The QR carries a per-launch token; connections without it are rejected.

## Build

Requires JDK 17+ and the Android SDK (API 35).

```bash
gradle wrapper        # once, to generate gradlew
./gradlew testDebugUnitTest assembleDebug
```

Install on the TV with `adb install app/build/outputs/apk/debug/app-debug.apk`.
Phone and TV must be on the same network (guest/hotel Wi-Fi with client
isolation will block it).

## ROMs

None are included. Use homebrew or dumps of cartridges you own.
