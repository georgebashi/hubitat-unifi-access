# UniFi Access events

## Doorbell notification stream

UniFi documents a local WebSocket at `wss://<console>:12445/api/v1/developer/devices/notifications`, authenticated with `Authorization: Bearer <API key>`, requiring Access 1.20.11+ and `view:device`. The reference documents remote-view/doorbell notifications here, not general door-position changes. See [UniFi Access API reference, section 11.1](https://assets.identity.ui.com/unifi-access/api_reference.pdf).

`drivers/UnifiAccessEvents.groovy` owns the long-lived socket. It uses Hubitat's `interfaces.webSocket.connect(url, pingInterval: ..., headers: ..., ignoreSSLIssues: ...)` form, supports the optional self-signed-certificate bypass, and retries with bounded delays (5, 15, 30, 60, 120, then 300 seconds). Hubitat examples confirm named headers and `ignoreSSLIssues`; the [WebSocket interface documentation](https://docs2.hubitat.com/en/developer/interfaces/websocket-interface) is authoritative for the API. The development Hubitat successfully connected to the console using this driver.

The driver accepts only documented `access.remote_view` and `access.remote_view.change` events. It requires `device_id`, `device_type`, and `request_id` on a remote-view event, then associates a subsequent change using `remote_call_request_id` and documented reason codes. It forwards a sanitized map to `parent.notifyAccessEvent(Map)`, including its configuration fingerprint so the parent can reject stale events after credentials or host settings change. It never forwards or logs raw messages, tokens, channels, MAC/IP addresses, names of actors, or other payload fields. Unknown or malformed events are ignored. Doorbell push events belong to the matching supported reader child; they do not imply relay or contact state.

## Door status and signed webhooks

UniFi's WebSocket is not a DPS feed. For low-latency door-position and other supported state events, the documented mechanism is a signed webhook (Access 2.2.10+): register with `POST /api/v1/developer/webhooks/endpoints` (`edit:webhook`) and subscribe to documented events such as `access.device.dps_status`. The webhook signature is `Signature: t=<unix-seconds>,v1=<hex>`; `v1` is HMAC-SHA256 using the endpoint secret over UTF-8 `<timestamp>.<raw-body>`. The API reference documents a five-second handler timeout. See [UniFi Access API reference, sections 11.2–11.7](https://assets.identity.ui.com/unifi-access/api_reference.pdf).

The app exposes an OAuth-enabled local mapping without an external relay. On Hubitat 2.5.1.183, live signed requests verified that `request.body` preserves the raw JSON (including UTF-8) and `request.headers` supplies the signature. The local URL uses the zero-argument `getFullLocalApiServerUrl()` plus the mapping path and private access token. The integration never parses/re-serializes the body before computing HMAC. Missing/invalid signatures, stale timestamps, replays, and oversized bodies are rejected; authenticated unknown events are acknowledged without producing device events. Request handling stays within UniFi's five-second timeout. Raw webhook bodies, signatures, and endpoint tokens are never logged.

The receiver should map DPS state only from a verified, supported event's documented door ID and `open`/`close` status. Do not infer sensor position from unlock, doorbell, or relay events. Polling remains the recovery path if webhook registration, delivery, signature access, or local routing is unavailable. These constraints avoid representing transient API gaps as physical state.

## Automatic relocking

The documented webhook event list includes `access.door.unlock`, but no corresponding general relay-relocked event. `access.temporary_unlock.end` covers a temporary unlock override, not a guaranteed notification for the end of every momentary release. A single read on the unlock event can therefore observe an unlocked relay and miss its subsequent automatic relock until the next regular poll.

The app performs bounded follow-up reads after unlock webhooks, accepted Hubitat unlock requests, or a newly observed unlocked relay. Follow-ups target 2, 4, 6, 8, 10, 15, 20, 30, 45, and 60 seconds; in-flight reads are shared rather than overlapped, and tracking expires after approximately 65 seconds. A fresh locked observation ends tracking early, with an initial grace period to avoid stopping on a read that predates the unlock. A relay that stays unlocked does not continually restart fast polling.

Only a returned relay observation updates native `Lock` state; a timer never declares the door locked. Normal polling remains the recovery path when event delivery is unavailable or a release outlasts the follow-up window. The short-release relock delay is normally a few seconds plus request latency rather than the full configured polling interval; this is reconciliation, not a guaranteed real-time relock event.

## Hubitat integration references

- [Hubitat WebSocket interface](https://docs2.hubitat.com/en/developer/interfaces/websocket-interface)
- [Hubitat OAuth and app mappings discussion (`request.body`)](https://community.hubitat.com/t/documentation-for-app-mapping-capabilities/42492)
- [Hubitat local app API URL discussion](https://community.hubitat.com/t/how-to-get-cloud-endpoint/266)
- [Hubitat WebSocket self-signed TLS option](https://community.hubitat.com/t/driver-connecting-to-insecure-url/119642)
- [Hubitat WebSocket header usage example](https://community.hubitat.com/t/release-lg-webos-tv-driver-oct-2024/144449/29)
