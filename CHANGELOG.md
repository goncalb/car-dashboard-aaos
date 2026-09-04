# Changelog

Android Automotive OS app. Version names follow the Homey companion's
numbering where they ship together; the Play versionCode is in
parentheses. Companion changes:
[com.barradas.cardashboard/CHANGELOG.md](https://github.com/goncalb/com.barradas.cardashboard/blob/main/CHANGELOG.md).

## 1.4.18 (29) (2026-09-03) — The car knows your home by name

- Info tab restructured: your Homey by name with owner, a Timeline of
  recent home events (times in the car's timezone: minutes within the
  hour, then clock time, Yesterday, date), a read-only Diagnostics
  screen for the technical rows, notification status, and Disconnect
  marked in red — always asking first.
- Timeline needs companion 1.4.18 and the optional API key described on
  its Cars tab; without it, everything else works and the Timeline stays
  private.
- Pairing texts are single short lines so the input stays visible above
  the keyboard on small screens (found on a Volvo XC40).
- Theme colour removed — hosts decide their own accents (Volvo ignores
  it, Renault paints its own red).

## 1.4.16 (27) (2026-09-02) — A demo home, and a quieter background

- Built-in demo home: enter "demo" as the Homey ID and the demo code to
  explore the full app — tiles, dimmable lights, scenes that visibly
  act, energy — without a Homey. Built for Google Play review;
  reviewers cannot own a hub, so the app carries its own.
- Fixed a crash when a geofence event arrived with nothing to act on
  (double broadcast-finish); it could kill the app silently in the
  background since 1.4.14.
- After pairing, the app opens on the Home grid. Targets Android 15.

## 1.4.14 (24) (2026-08-30) — Gates, and the car that closes the door behind you

- New **Gate** tile on the Home grid: one-tap control of driveway and
  garden gates, whether they are garage-door-style or lock-style
  devices. Green when closed/locked, amber when not, own icon.
- Gates join the arrival and departure notifications. With several
  barriers it is one notification per crossing, state-aware: a button
  for each pending action plus **Both/All** when there are two or more;
  anything already closed is mentioned rather than buttoned. Three
  buttons is the platform ceiling, so with more pending actions the
  first two get buttons and *All* covers the rest.
- Tiles set to *Close automatically* in the companion close or lock
  themselves at the geofence exit — only if still open — and the same
  notification carries the receipt ("Garage closed automatically ✓").
  Arrival is never automatic.
- Notification buttons act on several tiles at once (comma-joined ids)
  and report per tile; old-style intents still resolve to the garage.
- Blinds report open/closed from their position rather than the last
  motor command: an idle blind at 0% read "Open" before.

## 1.4.13 (23) (2026-08-27) — Lists that load while you drive

- Blinds, Locks and Sensors screens open in the host's loading state
  and fill in as a refresh. They used to open as a list with a
  placeholder row, and the switch from that to the real list counts as
  a structural change the host refuses while driving — so they sat on
  "Loading…" until parked. Every loading state in the app now matches
  the template type it turns into.

## 1.4.12 (22) (2026-08-27) — A row that explains the cut

- Every list starts with a standalone summary row — "12 lights · 3 on"
  parked, "Full list when parked" while driving. First position, because
  the host cuts lists from the bottom. The structure is identical in
  both states, so nothing reshapes while moving. Replaces a note that
  appeared on the first light.

## 1.4.11 (21) (2026-08-26) — Both ends, always

- Level screen presets ordered extremes-first (100 / 0 / 75 / 50 / 25)
  so fully open and fully closed stay within the rows the host shows
  while driving.

## 1.4.10 (20) (2026-08-25) — Where the energy goes

- Energy tile and detail screen: live flows (home, solar, battery, grid,
  EV charger) and Homey Energy's daily totals — consumption, solar yield
  and the share used at home, battery in/out, grid both ways, EV
  charging — with self-sufficiency computed. Sentences, not just numbers
  ("Exporting surplus").

## Earlier

- Geofenced garage warnings with headless Close/Open buttons, surviving
  reboots and Play updates; pairing with per-car tokens; Home grid with
  badge colours; Lights by room; Scenes; Info/diagnostics with metadata
  reporting. See the git history.
