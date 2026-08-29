# Car Dashboard for Android Automotive OS

Control your [Homey](https://homey.app) smart home from your car's own screen — and let the car take part in it.

A native Android Automotive OS app built with Google's [Android for Cars App Library](https://developer.android.com/training/cars/apps)
(templates, distraction-optimized, usable while driving). It talks to its companion Homey app over your Homey's
cloud API using per-car pairing tokens.

> **Companion app (required):** [Car Dashboard for Homey Pro]([HOMEY_REPO_URL]) — the Homey Pro app that
> exposes the whitelists, state and actions this client consumes.

|                |                                                     |
|----------------|-----------------------------------------------------|
| Google Play    | [PLAY_STORE_URL] *(internal testing — PM your Gmail on the community topic)* |
| Homey App Store (companion) | [HOMEY_APP_STORE_URL]                  |
| Community topic | [COMMUNITY_TOPIC_URL]                              |

![Home grid]([IMG] docs/home-grid.png)

## What it does

**Home grid** — big one-tap tiles for the things you actually use from the driver's seat: garage door,
gates, locks, blinds, window sensors, temperature, and a live energy tile. State on the badge
(green = settled, amber = wants attention), your label underneath.

**Categories** — Lights grouped by room (whole-room or per-light), Scenes (your chosen Homey Flows as
buttons), Blinds / Locks / Sensors lists, and a level screen for dimmers and blinds with steppers and
presets (100 / 0 / 75 / 50 / 25 — extremes first so both are reachable while driving).

**Energy** — live flows (home load, solar, battery, grid, EV charger) and daily totals from Homey
Energy: consumption, solar yield and how much of it you used at home, battery in/out, grid
import/export, EV charging. Self-sufficiency computed for you.

![Energy screen]([IMG] docs/energy.png)

**Geofencing** — the headline feature. The car keeps a geofence around your home (coordinates come
from your Homey's own location). Drive away with the garage or a gate still open and a notification
with a **Close** button appears in the car; arrive and you get **Open** buttons. With several barriers
it is one notification with a button per pending action plus **Both/All**. Each tile can instead be
set to close/lock itself on departure — always with a receipt notification. All of it works headless:
the app does not need to be open, and it survives reboots and Play Store updates.

**While driving** — the car host limits list length while moving. Every list therefore starts with a
permanent summary row ("12 lights · 3 on") that switches to "Full list when parked" when the cut is
active. Nothing reshapes, so the app never trips the host's task-step limits.

## Security model

No account login in the vehicle. Pairing happens once, with a short-lived code generated in the
companion app's settings; the car receives a **per-car, revocable token** that can only ever see the
devices you whitelisted. Tokens are managed (renamed, revoked) from the Homey side; the car stores its
token in local app storage and nothing else.

## Requirements

- A car running Android Automotive OS **with the Google Play Store** (Volvo, Polestar, Renault, GM,
  Honda, Ford lines, and others), Car App API level 1+.
- A Homey Pro running the companion app, reachable via Athom's cloud.
- Location permission "Allow all the time" if you want the geofence features (optional — everything
  else works without it).

## Building from source

Android Studio (Ladybug or newer), JDK 17.

```
git clone [CAR_REPO_URL]
cd car-dashboard
./gradlew :automotive:assembleDebug        # emulator/dev
./gradlew :automotive:bundleRelease        # Play upload
```

The AAOS emulator: SDK Manager → Automotive system images; run the `automotive` configuration.
On a real car the app must come through the Play Store (internal testing is enough) — retail AAOS
vehicles do not sideload.

## Pairing

1. Install the companion app on your Homey, open its settings, whitelist devices, create a pairing code.
2. In the car: enter your Homey ID and the code. Done — the token is issued and the code dies.

## Architecture in one paragraph

Kotlin, single `CarAppService` with a `TabTemplate` session (Home grid, Lights, Scenes, Info) and
pushed screens for categories, energy and levels. Poll-based state (10 s while visible) against the
companion's `/state`; actions via `/action` with the token in the request body. Geofencing via
`GeofencingClient` with a manifest-registered receiver; notifications use `CarAppExtender` and act
through a broadcast receiver without opening the app. No foreground services, no accounts, no
analytics, no third-party SDKs.

## Related projects

[Android Automotive by Simone Di Maio](https://homey.app/a/com.dimapp.aaos) pioneered the
Homey-on-AAOS idea with an OAuth relay and generic device rendering
([car app](https://github.com/s-dimaio/HomeyAutomotive) ·
[Homey app](https://github.com/s-dimaio/com.dimapp.aaos)). The two projects exchange ideas —
the Gate tile and the virtual-class handling in this app came out of that conversation.

## License

[GNU GPL v3.0](LICENSE) — free to use, study, modify and share; derivatives must stay open
under the same license. Same license as the related Android Automotive projects.

## Author

Gonçalo Barradas — [CONTACT_PLACEHOLDER]
