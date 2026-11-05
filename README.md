# Hubitat UniFi Protect

Hubitat Elevation drivers for UniFi Protect, built on Ubiquiti's **official local Protect Integration API** (API-key authentication).

> **Status:** early development. All drivers are tested on a Hubitat hub against UniFi Protect 7.3.74 with a UVC G4 Doorbell Pro.

## Drivers

| Driver | File | Purpose | Status |
|---|---|---|---|
| UniFi Protect Controller | [`unifi-protect-controller.groovy`](unifi-protect-controller.groovy) | Parent device. Holds the console address and API key, reads liveviews, viewers and cameras, creates child devices, keeps a real-time connection. | 0.3.0, hub-tested |
| UniFi Protect Viewport | [`component/unifi-protect-viewport.groovy`](component/unifi-protect-viewport.groovy) | One device per Protect viewer (e.g. a ViewPort). Shows the current liveview and switches liveviews. | 0.1.1, hub-tested |
| UniFi Protect Doorbell | [`component/unifi-protect-doorbell.groovy`](component/unifi-protect-doorbell.groovy) | One device per doorbell camera. Reports each ring as button 1 pushed. | 0.1.0, hub-tested |
| UniFi Protect Event Stream | [`component/unifi-protect-event-stream.groovy`](component/unifi-protect-event-stream.groovy) | Created automatically. Keeps the one live connection to Protect's events feed and passes events to the controller. | 0.1.0, hub-tested |

More device types (cameras, lights, chimes, sensors, …) can be added later as further drivers in `component/`.

## Setup

### 1. Create an API key
1. Open the console directly by IP address (not through unifi.ui.com) and sign in with a local administrator account.
1. Open **UniFi Protect** and go to **Integrations** (or browse to `https://<console-ip>/protect/integrations/`).
1. Create an API key and copy it. It's only shown once.

Never commit API keys to this repository or paste them into chats or tickets.

### 2. Install the drivers
1. In Hubitat, go to **Drivers Code** and import the drivers. The controller is always needed, the Event Stream is needed when you have a doorbell, and the other child drivers are needed for the devices you have:
   - `https://raw.githubusercontent.com/9r5n76yjmzs-design/hubitat-unifi-protect/main/unifi-protect-controller.groovy`
   - `https://raw.githubusercontent.com/9r5n76yjmzs-design/hubitat-unifi-protect/main/component/unifi-protect-event-stream.groovy`
   - `https://raw.githubusercontent.com/9r5n76yjmzs-design/hubitat-unifi-protect/main/component/unifi-protect-viewport.groovy`
   - `https://raw.githubusercontent.com/9r5n76yjmzs-design/hubitat-unifi-protect/main/component/unifi-protect-doorbell.groovy`

   These raw URLs need authentication while the repository is private. Until it's public, paste the code into **Drivers Code** directly.

### 3. Create the controller device
1. Go to **Devices** → **Add device** → **Virtual**, and choose **UniFi Protect Controller**.
1. In **Preferences**, enter the console IP address and the API key, leave **Real-time updates** on, choose a refresh interval, and click **Save**.
1. The controller checks the API (`apiStatus` should become `ok`), reads the liveviews, and creates a **UniFi Protect Viewport** device for each viewer and a **UniFi Protect Doorbell** device for each doorbell camera. With at least one doorbell, it also creates the **Event Stream** device.

## Controller device

| Attribute | Meaning |
|---|---|
| `apiStatus` | Whether Hubitat can use the Protect API: `ok`, `unauthorized` (key rejected), `unreachable`, `error`, `unconfigured` |
| `realtime` | The live connection to Protect's devices feed: `connected`, `connecting`, `disconnected` (retrying, with waits from 5 seconds up to 5 minutes), `off` |
| `eventStream` | The live connection to Protect's events feed, held by the Event Stream device: same values as `realtime`. `off` when there are no doorbells or real-time updates are off. |
| `applicationVersion` | Protect version |

Liveview details (`liveviews`, `defaultLiveview`) are kept only on the child devices that use them, such as the Viewport. Saving the controller's preferences removes them from controllers set up with 0.1.0.

**Real-time updates** keep a WebSocket open to Protect's official devices feed (`/v1/subscribe/devices`). A viewer's liveview or connection change made anywhere (the Protect app, the ViewPort itself, a lost network link) then shows up in Hubitat within a second or two. Each time the connection opens, the controller does a full refresh to catch up on anything it missed. If the connection drops, it checks the API at once, so Viewport values are cleared straight away when Protect is down.

The **refresh interval** stays on as a safety net. Liveviews that are created, renamed or deleted aren't sent on the devices feed and are picked up on the next refresh, or straight away if a viewer switches to a liveview Hubitat doesn't know yet.

## Viewport device

| Attribute | Meaning |
|---|---|
| `liveview` | Name of the liveview the viewer is showing now |
| `liveviewId` | ID of that liveview |
| `liveviews` | JSON list of all liveview names, for automations to offer as choices |
| `defaultLiveview` | Name of the liveview Protect marks as default |
| `connection` | Connection state reported by Protect (e.g. `CONNECTED`, `DISCONNECTED`) |
| `apiStatus` | The controller's API status, copied here whenever it changes (`ok`, `unauthorized`, `unreachable`, `error`, `unconfigured`) |

When the controller loses access to Protect (`apiStatus` is not `ok`), or Protect stops listing the viewer, the other values are cleared straight away so they never show a stale state: `liveview`, `liveviewId`, `defaultLiveview` and `connection` become `unknown`, and `liveviews` becomes `[]`. The next successful read from Protect fills them in again.

Commands:
- **Set Liveview**: switch to a liveview by name (case-insensitive) or ID.
- **Refresh**: re-read this viewer from Protect.

## Doorbell device

| Attribute | Meaning |
|---|---|
| `pushed` | Button 1 pushed: a ring (standard **PushableButton** capability, so Rule Machine's "button pushed" triggers work) |
| `numberOfButtons` | Always 1 |
| `lastRing` | Time of the last ring, from Protect (e.g. `2026-11-05T09:30:12-07:00`) |
| `connection` | Connection state reported by Protect (e.g. `CONNECTED`, `DISCONNECTED`) |
| `apiStatus` | The controller's API status, copied here whenever it changes |

Rings arrive through Protect's official events feed (`/v1/subscribe/events`, `ring` events), so **Real-time updates must be on** for rings to reach Hubitat. When the controller loses access to Protect, `connection` becomes `unknown`. `lastRing` is kept, because it records when a ring happened rather than a current state.

Doorbells are found by their model name: Protect has no doorbell flag, so any camera whose model contains "Doorbell" (for example "UVC G4 Doorbell Pro") gets a Doorbell device.

Commands:
- **Push** (button 1): simulates a ring from Hubitat, for testing automations without pressing the doorbell.
- **Refresh**: re-read this camera from Protect.

## Event Stream device

Created by the controller when there is at least one doorbell. It holds the one connection to Protect's events feed and hands each event to the controller, which routes it to the right child device. Hubitat gives each device a single WebSocket, and the controller's is used by the devices feed. One shared events connection serves every doorbell and future event-driven devices. It has no settings: the API key stays on the controller.

## Known limitations

- **Liveview list changes are seen on refresh.** New, renamed or deleted liveviews appear at the next refresh interval (see above). Viewer changes are real time.
- **Refresh is asynchronous.** The `liveview` attribute updates shortly after **Refresh** returns, not immediately.
- **The console's self-signed certificate is accepted without verification** (`ignoreSSLIssues`), as is usual for local UniFi consoles.

## API reference

- Official docs: https://developer.ui.com/protect/ (developed against v7.3.74)
- Local base URL: `https://<console-ip>/proxy/protect/integration`
- Auth: `X-API-KEY` header

## License

Released into the public domain under [The Unlicense](LICENSE).
