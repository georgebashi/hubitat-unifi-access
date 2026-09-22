# UniFi Access API findings

Official sources: [UniFi Access API reference (PDF)](https://assets.identity.ui.com/unifi-access/api_reference.pdf), [official API entry point](https://help.ui.com/hc/en-us/articles/30076656117655-Getting-Started-with-the-Official-UniFi-API), [supported consoles](https://help.ui.com/hc/en-us/articles/22230509487639-UniFi-Consoles-with-UniFi-Access-Support), [Access setup and ports](https://help.ui.com/hc/en-us/articles/17452334269975-Getting-Started-with-UniFi-Access), [required ports](https://help.ui.com/hc/en-us/articles/218506997-Required-Ports-Reference). Checked 2026-09-22.

## Setup and authentication

- UCG-Fiber is on Ubiquiti's supported UniFi Access console list. The **Access application must be installed and running** on the console; having only the Network application is insufficient. The API reference requires Access 1.9.1 or later and says this API is unavailable after upgrading to Identity Enterprise. [Sources: supported consoles](https://help.ui.com/hc/en-us/articles/22230509487639-UniFi-Consoles-with-UniFi-Access-Support), [API reference, introduction](https://assets.identity.ui.com/unifi-access/api_reference.pdf).
- Create the token in **Access > Settings > General > Advanced > API Token**. Choose its name, validity period, and permission scopes; copy it when shown, because it is displayed only once. Send `Authorization: Bearer <token>`. Expired or deleted tokens fail authentication. [Source: API reference, sections 1.1 and 2.1–2.7](https://assets.identity.ui.com/unifi-access/api_reference.pdf).
- The API reference and required-ports article specify local `https://<console-ip>:12445/api/v1/developer` and a self-generated, untrusted server certificate. The current Access setup article instead lists **12455** for OpenAPI when enabled. This is an inconsistency in Ubiquiti's own documentation; confirm the working port in the user's Access installation before fixing a default. Prefer a trusted certificate and normal TLS verification when possible. [Sources: API reference, sections 1.2 and 2.6](https://assets.identity.ui.com/unifi-access/api_reference.pdf), [ports](https://help.ui.com/hc/en-us/articles/218506997-Required-Ports-Reference), [Access setup](https://help.ui.com/hc/en-us/articles/17452334269975-Getting-Started-with-UniFi-Access).
- Responses use an envelope such as `{"code":"SUCCESS","msg":"success","data":...}`. Check both HTTP status and `code`; 401 means invalid authentication, 403 means insufficient scope, and 429 means rate limiting. [Source: API reference, sections 1.4 and 2.8](https://assets.identity.ui.com/unifi-access/api_reference.pdf).

## Door API

All paths below are relative to `https://<console-ip>:12445` in the API reference. [Source: API reference, sections 7.7–7.11](https://assets.identity.ui.com/unifi-access/api_reference.pdf).

| Action | Method and path | Token scope | Semantics |
| --- | --- | --- | --- |
| Discover doors | `GET /api/v1/developer/doors` | `view:space` | Returns `data` array of doors. |
| Read one door | `GET /api/v1/developer/doors/:id` | `view:space` | Fields include `id`, `name`, `full_name`, `is_bind_hub`, `door_lock_relay_status` (`lock` or `unlock`), and `door_position_status` (`open` or `close`; null when no sensor is connected). Treat relay and physical position as separate states. |
| Pulse unlock | `PUT /api/v1/developer/doors/:id/unlock` | `edit:space` | Remotely triggers an unlock for a door bound to a hub. Optional JSON `actor_id` and `actor_name` must be supplied together; otherwise the token name is logged. Optional `extra` is passed through to webhook payloads. This endpoint does **not** document a persistent unlocked state. |
| Temporary lock rule | `PUT /api/v1/developer/doors/:id/lock_rule` | `edit:space` | Body `{"type":"keep_lock"}` or `{"type":"keep_unlock"}` changes the temporary locking rule; `reset` restores the initial rule, `lock_early` ends an unlock schedule early, and `lock_now` terminates an unlock schedule and temporary unlock. `custom` accepts a duration in minutes. Requires Access 1.24.6 or later. Do not map a generic Hubitat `lock()` directly to `lock_now` without explaining its schedule effects. |

The PDF has a stale cross-reference spelling `remote_unlock` in the door schema, but its actual operation heading, request URL, and cURL sample use `/unlock`; implement `/unlock`. [Source: API reference, sections 7.7–7.9](https://assets.identity.ui.com/unifi-access/api_reference.pdf).

## Events

- For initial state, poll `GET /doors` or `GET /doors/:id`. The API also has `POST /api/v1/developer/system/logs` (`view:system_log`) with `topic: "door_openings"`, time filters, and pagination; this supplies history, not physical status. [Source: API reference, sections 7.7–7.8 and 9.2](https://assets.identity.ui.com/unifi-access/api_reference.pdf).
- Webhooks require Access 2.2.10 or later. Register with `POST /api/v1/developer/webhooks/endpoints` (`edit:webhook`) and list with `GET` (`view:webhook`); subscription includes `name`, `endpoint`, and `events`. Relevant events are `access.device.dps_status` (door position sensor change), `access.door.unlock` (all unlock events), `access.temporary_unlock.start/end`, and `access.unlock_schedule.activate/deactivate`. The latter four require Access 3.3.10 or later. Webhook `Signature` is `t=<unix-seconds>,v1=<hex>` where `v1` is HMAC-SHA256 over `<timestamp>.<raw-body>` with the endpoint secret. The reference says handlers have a 5-second timeout. [Source: API reference, sections 11.2–11.7](https://assets.identity.ui.com/unifi-access/api_reference.pdf).
- `wss://<console-ip>:12445/api/v1/developer/devices/notifications` (`view:device`, Access 1.20.11+) is documented for **doorbell and remote-view notifications**. It is not documented as a general door-position feed. [Source: API reference, section 11.1](https://assets.identity.ui.com/unifi-access/api_reference.pdf).

## MVP recommendation and unknowns

Start with a configurable console host/port and API token, door discovery, periodic door reads, and momentary `unlock` on a selected door. Request only `view:space` and `edit:space` for this MVP. Expose `door_lock_relay_status` separately from `door_position_status`, with an unknown position when no DPS exists. Defer persistent lock control until its schedule impact is chosen explicitly; use webhooks later if lower event latency is needed. This is an implementation recommendation based on the documented API, not a Ubiquiti requirement.

Subsequent read-only verification on 2026-09-22 succeeded against port 12445 using the provided development token. Inventory: one bound door and one online UA-Hub-Door-Mini. The door reported `door_position_status: "none"`, unlike the reference's null description. Device `data` was a nested array of device groups. No device actions were issued. Installed Access version and lock-rule behavior remain unverified.
