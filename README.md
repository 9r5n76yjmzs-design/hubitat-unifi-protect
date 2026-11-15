# Hubitat UniFi Protect

Hubitat Elevation drivers for UniFi Protect, built on Ubiquiti's **official local Protect Integration API** (API-key authentication).

> **Status:** early development. All drivers are tested on a Hubitat hub against UniFi Protect 7.3.74 with a UVC G4 Doorbell Pro, and package detection (controller 0.4.0, Doorbell 0.2.0) was tested on a hub. Glass break (controller 0.5.0, Glass Break 0.1.0) is not yet tested on a hub.

## Drivers

| Driver | File | Purpose | Status |
|---|---|---|---|
| UniFi Protect Controller | [`unifi-protect-controller.groovy`](unifi-protect-controller.groovy) | Parent device. Holds the console address and API key, reads liveviews, viewers and cameras, creates child devices, keeps a real-time connection. | 0.5.0; glass-break routing not yet hub-tested |
| UniFi Protect Viewport | [`component/unifi-protect-viewport.groovy`](component/unifi-protect-viewport.groovy) | One device per Protect viewer (e.g. a ViewPort). Shows the current liveview and switches liveviews. | 0.1.2, hub-tested |
| UniFi Protect Doorbell | [`component/unifi-protect-doorbell.groovy`](component/unifi-protect-doorbell.groovy) | One device per doorbell camera. Reports each ring as button 1 pushed and each package detection as button 2 pushed. | 0.2.0, hub-tested |
| UniFi Protect Glass Break | [`component/unifi-protect-glass-break.groovy`](component/unifi-protect-glass-break.groovy) | One device per camera that can recognize breaking glass. Reports a glass break as a shock sensor, for Hubitat Safety Monitor, and shows the camera's sound detections. | 0.1.0, not yet hub-tested |
| UniFi Protect Event Stream | [`component/unifi-protect-event-stream.groovy`](component/unifi-protect-event-stream.groovy) | Created automatically. Keeps the one live connection to Protect's events feed and passes events to the controller. | 0.1.1, hub-tested |

More device types (cameras, lights, chimes, sensors, …) can be added later as further drivers in `component/`.

## Setup

### 1. Create an API key
1. Open the console directly by IP address (not through unifi.ui.com) and sign in with a local administrator account.
1. Open **UniFi Protect** and go to **Integrations** (or browse to `https://<console-ip>/protect/integrations/`).
1. Create an API key and copy it. It's only shown once.

Never commit API keys to this repository or paste them into chats or tickets.

### 2. Install the drivers

**With Hubitat Package Manager (recommended):** in HPM, choose **Install** → **From a URL**, enter `https://raw.githubusercontent.com/9r5n76yjmzs-design/hubitat-unifi-protect/main/packageManifest.json`, and follow the steps. HPM installs all five drivers and keeps them updated. It can only install while this repository is public.

**Manually:**
1. In Hubitat, go to **Drivers Code** and import the drivers. The controller is always needed, the Event Stream is needed when you have a doorbell or a Glass Break device, and the other child drivers are needed for the devices you have:
   - `https://raw.githubusercontent.com/9r5n76yjmzs-design/hubitat-unifi-protect/main/unifi-protect-controller.groovy`
   - `https://raw.githubusercontent.com/9r5n76yjmzs-design/hubitat-unifi-protect/main/component/unifi-protect-event-stream.groovy`
   - `https://raw.githubusercontent.com/9r5n76yjmzs-design/hubitat-unifi-protect/main/component/unifi-protect-viewport.groovy`
   - `https://raw.githubusercontent.com/9r5n76yjmzs-design/hubitat-unifi-protect/main/component/unifi-protect-doorbell.groovy`
   - `https://raw.githubusercontent.com/9r5n76yjmzs-design/hubitat-unifi-protect/main/component/unifi-protect-glass-break.groovy`

   These raw URLs only work while the repository is public. Otherwise, paste the code into **Drivers Code** directly.

### 3. Create the controller device
1. Go to **Devices** → **Add device** → **Virtual**, and choose **UniFi Protect Controller**.
1. In **Preferences**, enter the console's IPv4 address (for example `192.168.1.1`; host names aren't accepted) and the API key, leave **Real-time updates** on, choose a refresh interval, and click **Save**.
1. The controller checks the API (`apiStatus` should become `ok`), reads the liveviews, and creates a **UniFi Protect Viewport** device for each viewer and a **UniFi Protect Doorbell** device for each doorbell camera, and a **UniFi Protect Glass Break** device for each camera that can recognize breaking glass. With at least one doorbell or Glass Break device, it also creates the **Event Stream** device.

## Controller device

| Attribute | Meaning |
|---|---|
| `apiStatus` | Whether Hubitat can use the Protect API: `ok`, `unauthorized` (key rejected), `unreachable`, `error`, `unconfigured` |
| `realtime` | The live connection to Protect's devices feed: `connected`, `connecting`, `disconnected` (retrying, with waits from 5 seconds up to 5 minutes), `off` |
| `eventStream` | The live connection to Protect's events feed, held by the Event Stream device: same values as `realtime`. `off` when there are no doorbells or Glass Break devices, or real-time updates are off. |
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
| `pushed` | Button 2 pushed: the doorbell camera detected a package |
| `numberOfButtons` | Always 2 |
| `lastRing` | Time of the last ring, from Protect (e.g. `2026-11-05T09:30:12-07:00`) |
| `lastPackage` | Time of the last reported package detection, from Protect |
| `connection` | Connection state reported by Protect (e.g. `CONNECTED`, `DISCONNECTED`) |
| `apiStatus` | The controller's API status, copied here whenever it changes |

Rings arrive through Protect's official events feed (`/v1/subscribe/events`, `ring` events), so **Real-time updates must be on** for rings to reach Hubitat. When the controller loses access to Protect, `connection` becomes `unknown`. `lastRing` and `lastPackage` are kept, because they record when something happened rather than a current state.

### Package detection

When the doorbell camera's smart detection sees a package (a `smartDetectZone` event whose `smartDetectTypes` include `package`), the Doorbell reports **button 2 pushed** and sets `lastPackage`. It arrives on the same events feed as rings. Package detection has to be switched on for the camera in Protect, and the camera has to support it.

- **Each Protect event is reported once.** Protect sends the same event again as it changes or ends; those repeats are dropped.
- **Every new Protect event is reported.** Protect is expected to raise one event when a new package arrives. If it turns out to report the same package more than once, Preferences has an ignore time (off by default). Rings are never held back.
- **The driver only reports.** What happens next is up to your automations, for example:
  - *Show the door:* install [Package Notification](https://github.com/9r5n76yjmzs-design/hubitat-package-notification), an extension to [Doorbell Display](https://github.com/9r5n76yjmzs-design/hubitat-doorbell-display). When button 2 is pushed it runs **Push** with button 1 on the Doorbell, so Doorbell Display shows the door. Everything else that reacts to a ring runs too.
  - Anything else Hubitat can do on a button press: a notification, a light, a chime.

Only doorbell cameras report packages to Hubitat. Smart detections from other cameras, and other detection types (person, vehicle, animal), are not used yet.

Doorbells are found by their model name: Protect has no doorbell flag, so any camera whose model contains "Doorbell" (for example "UVC G4 Doorbell Pro") gets a Doorbell device.

Commands:
- **Push** with button 1: simulates a ring from Hubitat, for testing automations without pressing the doorbell.
- **Push** with button 2: simulates a package detection. Test presses are never held back by the ignore time.
- **Refresh**: re-read this camera from Protect.

## Glass Break device

Created for each camera that Protect says can recognize breaking glass (the camera's `featureFlags.smartDetectAudioTypes` includes `alrmGlassBreak`), named after the camera, for example *Garage Glass Break*.

| Attribute | Meaning |
|---|---|
| `shock` | `detected` while the camera hears breaking glass, then `clear` (standard **ShockSensor** capability, which Hubitat uses for glass-break sensors) |
| `lastGlassBreak` | Time of the last glass break, from Protect |
| `glassBreakDetection` | Whether glass break detection is turned on for this camera in Protect: `on` or `off` |
| `audioDetectionsSupported` | The sounds this camera can recognize, for example `Smoke alarm, CO alarm, Glass break` |
| `audioDetectionsOn` | The sounds turned on for this camera in Protect, or `none` |
| `lastAudioDetection` | The last sound the camera recognized, of any kind (for example `Dog bark`) |
| `lastAudioDetectionTime` | When that was, from Protect |
| `connection` | Connection state reported by Protect (e.g. `CONNECTED`, `DISCONNECTED`) |
| `apiStatus` | The controller's API status, copied here whenever it changes |

Sound detections arrive on the same events feed as rings (`smartAudioDetect` events), so **Real-time updates must be on**. Protect sends an event when the sound starts and again when it ends; each event is reported once. `shock` becomes `detected` when the glass break starts and `clear` when Protect says it ended. If Protect never says so (for example after a lost connection), the device clears it anyway after the time set in Preferences (1 minute by default). A second glass break is reported as a new `detected` event even if the first has not cleared yet. Sound names Protect adds in later versions are ignored until the drivers know them.

**Using it with Hubitat Safety Monitor:** in HSM, open **Intrusion-Away** (and Intrusion-Home or Intrusion-Night if you want), and select the Glass Break devices among the shock or glass-break sensors. A glass break heard while HSM is armed in that state then raises an intrusion alert. A shock sensor never blocks arming the way an open contact sensor can.

Before relying on it:
- **Turn on glass break detection for the camera in Protect.** The camera must be able to hear it, and Protect only reports the sounds that are turned on. The device warns in the log, once, when it's off, and `glassBreakDetection` shows the setting.
- **Camera sound detection is a helpful extra signal, not a certified alarm sensor.** It can miss a break, and other sounds (a dropped glass or plate) can set it off. If HSM sounds a siren, an alert delay and a cancel button (in HSM's arming options) limit false alarms.
- **It only works while Hubitat can reach Protect.** When the controller loses access, `connection` becomes `unknown`.
- **Glass-break sensors that Protect itself manages** (Protect sensors or the alarm hub) report different events and are not supported yet.

Commands:
- **Test Glass Break**: sets `shock` to `detected` for 5 seconds, so automations can be tested. **If HSM uses this device and is armed, this raises a real intrusion alert.**
- **Refresh**: re-read this camera from Protect.

## Event Stream device

Created by the controller when there is at least one doorbell or Glass Break device. It holds the one connection to Protect's events feed and hands each event to the controller, which routes it to the right child device. Hubitat gives each device a single WebSocket, and the controller's is used by the devices feed. One shared events connection serves every doorbell (rings and package detections), every Glass Break device (sound detections) and future event-driven devices. It has no settings: the API key stays on the controller.

## Known limitations

- **Liveview list changes are seen on refresh.** New, renamed or deleted liveviews appear at the next refresh interval (see above). Viewer changes are real time.
- **Refresh is asynchronous.** The `liveview` attribute updates shortly after **Refresh** returns, not immediately.
- **Package detection depends on Protect.** How often Protect reports a package that stays at the door, and whether it reports one at all, is decided by the camera's smart detection settings and zones in Protect.
- **The console's self-signed certificate is accepted without verification** (`ignoreSSLIssues`), as is usual for local UniFi consoles. See [SECURITY.md](SECURITY.md) for what that means and how to limit the risk.

## API reference

- Reference pages (version 7.4): https://developer.ui.com/protect/v7.4.99/gettingstarted
- OpenAPI specification, for exact field names: https://developer.ui.com/protect/v7.4.99/openapi.json
- Postman collection, for trying requests by hand: https://developer.ui.com/protect/v7.4.99/postman-collection.json
- The version is part of these URLs and changes when Protect updates. The drivers were developed against 7.3.74; package detection and sound detection were checked against the 7.4.99 specification.
- Local base URL: `https://<console-ip>/proxy/protect/integration`
- Auth: `X-API-KEY` header

## Acknowledgements

Inspired by tomwpublic's [hubitat_unifiProtect](https://github.com/tomwpublic/hubitat_unifiProtect) and [hubitat_unifiEvents](https://github.com/tomwpublic/hubitat_unifiEvents) drivers (Apache-2.0), in particular their layout of a parent controller driver with one child driver per device type. Those drivers use Protect's private API; this project is a new implementation on the official Integration API and contains none of their code.

## License

Released into the public domain under [The Unlicense](LICENSE).
