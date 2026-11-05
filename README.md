# Hubitat UniFi Protect

Hubitat Elevation drivers for UniFi Protect, built on Ubiquiti's **official local Protect Integration API** (API-key authentication).

> **Status:** early development. The controller (0.2.0, with real-time updates) and the Viewport driver (0.1.0) are tested on a Hubitat hub against UniFi Protect 7.3.74.

## Drivers

| Driver | File | Purpose | Status |
|---|---|---|---|
| UniFi Protect Controller | [`unifi-protect-controller.groovy`](unifi-protect-controller.groovy) | Parent device. Holds the console address and API key, reads liveviews and viewers, creates child devices, keeps a real-time connection. | 0.2.0, hub-tested |
| UniFi Protect Viewport | [`component/unifi-protect-viewport.groovy`](component/unifi-protect-viewport.groovy) | One device per Protect viewer (e.g. a ViewPort). Shows the current liveview and switches liveviews. | 0.1.0, hub-tested |
| UniFi Protect Doorbell | — | Doorbell ring events (official `ringEvent`). | planned |

More device types (cameras, lights, chimes, sensors, …) can be added later as further drivers in `component/`.

## Setup

### 1. Create an API key
1. Open the console directly by IP address (not through unifi.ui.com) and sign in with a local administrator account.
1. Open **UniFi Protect** and go to **Integrations** (or browse to `https://<console-ip>/protect/integrations/`).
1. Create an API key and copy it. It's only shown once.

Never commit API keys to this repository or paste them into chats or tickets.

### 2. Install the drivers
1. In Hubitat, go to **Drivers Code** and import both drivers:
   - `https://raw.githubusercontent.com/9r5n76yjmzs-design/hubitat-unifi-protect/main/unifi-protect-controller.groovy`
   - `https://raw.githubusercontent.com/9r5n76yjmzs-design/hubitat-unifi-protect/main/component/unifi-protect-viewport.groovy`

   These raw URLs need authentication while the repository is private. Until it's public, paste the code into **Drivers Code** directly.

### 3. Create the controller device
1. Go to **Devices** → **Add device** → **Virtual**, and choose **UniFi Protect Controller**.
1. In **Preferences**, enter the console IP address and the API key, leave **Real-time updates** on, choose a refresh interval, and click **Save**.
1. The controller checks the API (`apiStatus` should become `ok`), reads the liveviews and creates a **UniFi Protect Viewport** device for each viewer.

## Controller device

| Attribute | Meaning |
|---|---|
| `apiStatus` | Whether Hubitat can use the Protect API: `ok`, `unauthorized` (key rejected), `unreachable`, `error`, `unconfigured` |
| `realtime` | The live connection to Protect: `connected`, `connecting`, `disconnected` (retrying, with waits from 5 seconds up to 5 minutes), `off` |
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
