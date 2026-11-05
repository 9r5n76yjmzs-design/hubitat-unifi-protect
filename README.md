# Hubitat UniFi Protect

Hubitat Elevation drivers for UniFi Protect, built on Ubiquiti's **official local Protect Integration API** (API-key authentication).

> **Status:** early development (0.1.0). The controller and Viewport drivers are written and parse-checked, but not yet tested on a hub.

## Drivers

| Driver | File | Purpose | Status |
|---|---|---|---|
| UniFi Protect Controller | [`unifi-protect-controller.groovy`](unifi-protect-controller.groovy) | Parent device. Holds the console address and API key, reads liveviews and viewers, creates child devices. | 0.1.0, untested |
| UniFi Protect Viewport | [`component/unifi-protect-viewport.groovy`](component/unifi-protect-viewport.groovy) | One device per Protect viewer (e.g. a ViewPort). Shows the current liveview and switches liveviews. | 0.1.0, untested |
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
1. In **Preferences**, enter the console IP address and the API key, choose a refresh interval, and click **Save**.
1. The controller checks the API (`apiStatus` should become `ok`), reads the liveviews and creates a **UniFi Protect Viewport** device for each viewer.

## Viewport device

| Attribute | Meaning |
|---|---|
| `liveview` | Name of the liveview the viewer is showing now |
| `liveviewId` | ID of that liveview |
| `liveviews` | JSON list of all liveview names, for automations to offer as choices |
| `defaultLiveview` | Name of the liveview Protect marks as default |
| `connection` | Connection state reported by Protect (e.g. `CONNECTED`, `DISCONNECTED`). Becomes `unknown` as soon as the controller loses access to Protect (`apiStatus` not `ok`) or Protect stops reporting the viewer, so it never shows a stale value. |
| `apiStatus` | The controller's API status, copied here whenever it changes (`ok`, `unauthorized`, `unreachable`, `error`, `unconfigured`). Anything other than `ok` means the other values may be out of date. |

Commands:
- **Set Liveview**: switch to a liveview by name (case-insensitive) or ID.
- **Refresh**: re-read this viewer from Protect.

## Known limitations (0.1.0)

- **Changes made outside Hubitat are only seen on refresh.** If someone changes the viewer's liveview in the Protect app, Hubitat picks it up at the next refresh interval or when **Refresh** is run. Real-time updates through the official devices WebSocket are planned.
- **Refresh is asynchronous.** The `liveview` attribute updates shortly after **Refresh** returns, not immediately.
- **The console's self-signed certificate is accepted without verification** (`ignoreSSLIssues`), as is usual for local UniFi consoles.

## API reference

- Official docs: https://developer.ui.com/protect/ (developed against v7.3.74)
- Local base URL: `https://<console-ip>/proxy/protect/integration`
- Auth: `X-API-KEY` header

## License

Released into the public domain under [The Unlicense](LICENSE).
