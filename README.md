# Hubitat UniFi Protect

Hubitat Elevation driver(s) for UniFi Protect, built on Ubiquiti's **official local Protect Integration API** (API-key authentication).

> **Status:** not started. Nothing here is usable yet.

## Goals

- Expose doorbell ring events to Hubitat (official `ringEvent` over the events WebSocket).
- List Protect liveviews (with the default liveview marked) so automations can pick a layout from a list instead of typing names.
- Switch a UniFi ViewPort (viewer) to a given liveview, and back to the default.

These support the doorbell display automation in the Hubitat Context-Aware Endpoints project. Restore and sequencing logic belongs in the automation, not in the driver.

## API reference

- Official docs: https://developer.ui.com/protect/
- Local base URL: `https://<console-ip>/proxy/protect/integration`
- Auth: `X-API-Key` header. Create the key in the local console under **Integrations**.

Never commit API keys, console credentials or other secrets to this repository.

## License

Released into the public domain under [The Unlicense](LICENSE).
