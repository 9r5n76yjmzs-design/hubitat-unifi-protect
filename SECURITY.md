# Security

These drivers run on a Hubitat Elevation hub and use UniFi Protect's official local Integration API. The goal is that installing them does not widen what an attacker can reach through the hub.

## Reporting a problem

Please report security problems privately to the maintainer, Michael Dailous, rather than in a public issue.

## What the drivers can reach

- **Outbound only, to one address:** the console's IPv4 address from the controller's Preferences, over HTTPS and secure WebSockets (`/proxy/protect/integration`). Host names, ports, paths and user info aren't accepted, so the API key can't be sent to another host by a typo or a crafted value.
- **No inbound endpoints:** the drivers open no listening ports, OAuth endpoints or cloud callbacks, and don't use the UniFi cloud connector.
- **Protect object IDs are checked** (24 hexadecimal characters) before they are put into a request URL.

## Secrets

| Secret | Where it is kept | Notes |
|---|---|---|
| Protect API key | Controller's **API key** preference (password type, masked on the page) | Never stored in `state`, never logged. The Event Stream device fetches it from the controller for each connection and doesn't keep it. |

Hubitat has no encrypted secret store. The key is still kept **unencrypted on the hub and in hub backups**, and anyone with admin access to the hub can read it. If you think the key has been exposed, delete it in UniFi Protect → Integrations and create a new one.

## Known and accepted risks

- **The console's certificate isn't verified** (`ignoreSSLIssues`). Local UniFi consoles use self-signed certificates, and Hubitat can't pin a specific certificate. Someone able to intercept traffic between the hub and the console could capture the API key. The real protection is network isolation between untrusted devices and the hub/console segment; firewall policy belongs to Network & Services Hardening. Where Protect offers it, give the key the least access that works.
- **Commands are available to anything that can control the devices:** Hubitat apps you grant them to, and the Maker API if it exposes them. Set Liveview only accepts liveviews Protect reports.
- **Names come from Protect** (cameras, viewers, liveviews). Angle brackets are removed before they are shown, so a name can't add markup to the hub's pages.

## Planned: certificate validation

A private certificate store is planned as part of the network restructuring and hardening work. Once devices present valid certificates from it, certificate checks will be turned on across the drivers (no more `ignoreSSLIssues`), and self-signed certificates will no longer be accepted. Until then, the owner accepts the risk described above.

## Recommended hub settings (owner action)

- Turn on **Hub Login Security**.
- In the **Maker API**, expose only the devices that need it, and keep its access token private.
- Treat **hub backups as secret**: they contain the API key.

## Secure coding standard

Every change follows these rules. A line may opt out of one checked rule only with a reviewed marker that gives the reason, for example `// security-check: allow S1 (reason)`. Rules marked *(checked)* are enforced by [`.github/scripts/security-check.sh`](.github/scripts/security-check.sh), which runs on every pull request with the Groovy parse check.

1. **S1 (checked):** Secrets are never stored in `state`; use a `password`-type preference.
2. **S2 (checked):** Secret values are never written to the logs. Request headers are never logged.
3. **S3 (checked):** Raw inbound messages are never logged in full at info level or above.
4. **S4 (checked):** No credentials are hard-coded.
5. **S5 (checked):** Pages never load remote resources (images, scripts, styles).
6. **S6 (checked):** Links that open a new tab use `rel="noopener noreferrer"`.
7. **S7 (checked):** Debug logging is off by default, and turns itself off after 30 minutes when switched on.
8. **S8 (checked):** Any file that skips certificate checks explains why and points here.
9. **S9 (checked):** Continuity records, keys and `.env` files are never committed.
10. **Validate all input** from users, apps, Protect and the network before use: addresses must be IPv4, IDs must match Protect's format.
11. **Text from outside the hub** has angle brackets removed before it is shown in attributes, data values or labels.
12. **Least privilege:** use only the API access the code needs; one shared events connection instead of one per device.
13. **Bound everything:** retries and reconnect back-off have limits (5 seconds to 5 minutes).
14. **No new attack surface:** no listening ports, OAuth/cloud endpoints, or remote code or content loading.
