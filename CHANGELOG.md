# UniFi Protect - Changelog

All notable changes to this project will be documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.1.0/),
and this project adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).
Each driver carries its own version; the package version follows the controller.

## [0.3.1] - 2026-11-11
First packaged release (Hubitat Package Manager).

### Added
- `SECURITY.md` with the secure coding standard, and CI that runs a security check and a Groovy parse check on every pull request.
- Package manifest for Hubitat Package Manager.

### Changed
- The console address must be a plain IPv4 address; host names are no longer accepted, so the API key can't be sent to another host.
- Controller debug logging defaults to off. Viewport (0.1.2), Doorbell (0.1.1) and Event Stream (0.1.1) switch debug logging off after 30 minutes.

### Security
- Protect object IDs are checked before they are used in request URLs or to create child devices.
- Angle brackets are removed from Protect-supplied names and states before they are shown on the hub.

## [0.3.0] - 2026-11-05
### Added
- UniFi Protect Doorbell driver: each ring is reported as button 1 pushed (PushableButton), with `lastRing`; Push simulates a ring.
- UniFi Protect Event Stream driver: one shared connection to Protect's events feed, created automatically when there is a doorbell.

## [0.2.0] - 2026-11-05
### Added
- Real-time viewer updates over Protect's devices WebSocket, with reconnect back-off (5 seconds to 5 minutes) and a catch-up refresh on reconnect.

### Changed
- Liveview details are kept only on the child devices that use them.
- Commands and attributes follow Hubitat's documented driver format.

## [0.1.0] - 2026-11-05
### Added
- UniFi Protect Controller driver on the official local Protect Integration API (API key authentication).
- UniFi Protect Viewport driver: current liveview, liveview list and Set Liveview.
- Viewport values are cleared while Protect can't be reached, so they never show a stale state.
