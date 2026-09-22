# UniFi Access hardware coverage

Scope: physical hardware and the operations exposed by the [official Access API reference](https://assets.identity.ui.com/unifi-access/api_reference.pdf), sections 7, 8, and 11. User, visitor, group, credential, and general policy administration are excluded.

## Implemented surfaces

| Resource | Hubitat representation | API coverage |
| --- | --- | --- |
| Doors | Stable door child with native `Lock`, optional contact observations via `ContactSensor`, relay/position attributes, availability, refresh, and custom commands | List doors; momentary unlock; native lock delegates to lock now; read/set temporary lock rules, including keep locked, keep unlocked, timed unlock, reset, early schedule termination, and lock now. |
| Hubs and other hardware | Generic physical device child for every returned device type | Device inventory, model, connectivity/adoption, refresh, and associated door availability. Nested device groups are normalized. Missing children are retained. |
| Readers | Hardware child with settings/status and typed custom commands | Read/update all documented access methods returned by the device; PIN shuffling; supported face-sensitivity combinations. Empty settings are treated as unsupported. |
| Intercom and Reader Pro | Hardware controls plus a separate one-button doorbell child | Trigger/cancel ringing, optional Intercom room; documented WebSocket and webhook ring/completion events. Duplicate ring deliveries do not create duplicate button events. |
| Request-to-enter input | Per-door one-button child, created on its first verified REN event | Routes documented REN notifications with a null reader device by door ID; never invents a reader association. |
| Gate Hub | Door child with gate-specific custom commands | Open, close, stop, and entry/exit direction through the documented unlock endpoint query parameters. Explicit commands may attempt these operations; discovery never actuates them to probe support. |
| Emergency state | Dedicated controller child | Read emergency status; lockdown, evacuation, and clear actions. These affect the whole installation. |
| Notifications | Dedicated event transport child | Authenticated WebSocket connection, status, reconnect with bounded backoff, allowlisted remote-view event parsing, and per-device routing. |
| Physical-state events | Signed local app webhook endpoint | Managed subscription lifecycle, HMAC validation of raw body, timestamp/replay checks, DPS contact updates, and reconciliation of unlock/rule/emergency events. |

All actions and event transports are enabled without opt-in preferences. A successful API response records acceptance, not predicted movement or contact state. Writes are never automatically retried. For commands and argument details see [commands.md](commands.md).

## Capability discovery

Inventory and action routing do not maintain a product/SKU registry. Device model strings are informational. The integration probes each device's read-only settings endpoint and each door's lock-rule endpoint, validates their response schemas, and uses the returned fields as capability evidence. Refresh can repeat the probes; unavailable credentials or transport errors are not evidence of an unsupported feature.

The documented gate-control and doorbell-trigger endpoints are write-only. Opening a gate or ringing a bell is not a capability probe, and an HTTP OPTIONS response would not prove model support. These controls therefore have unknown support until API evidence exists, but explicit command invocations may attempt them without a support declaration. This handles new and unlisted models without editing source or pretending that an unsafe discovery action is read-only.

Reader access-method settings are gated by the actual settings response, not inferred from the model or broad capability flags. The development Mini, for example, advertises a PIN capability but returns an empty access-method map. Authenticated incoming doorbell events establish notification support and create the corresponding button child without a product-name check; notification support does not prove support for the separate POST action.

The API does not guarantee a universal one-to-one hub-to-door association. When available, the integration uses hub role/capabilities and location ID, avoiding accidental association with a reader. Missing associations stay `unverified`; an explicitly invoked command can still target a fresh API-confirmed bound door. A known-offline associated hub or stale door data prevents the command. This supports multi-door layouts without inventing connectivity evidence.

## State semantics and limits

- `lockRelay` is not physical contact position. Only observed `open`/`close` DPS values produce standard contact events. Missing/null/`none` values never mean closed.
- Standard contact retains its last known value when data becomes unavailable; custom availability/freshness attributes describe whether it is current.
- Native `Lock.unlock()` is momentary release; `Lock.lock()` invokes `lockNow()`, ending active scheduled/temporary unlocking. These schedule effects also apply when commands originate in HomeKit or another native integration. Native `lock` reports observed relay state, or `unknown` when unavailable; it is not proof of physical door closure.
- Doorbell button events come from verified incoming notifications, not command acceptance or log polling.
- The reference does not document elevator-specific relay/floor control or camera/video-stream operations. Those functions are not claimed as supported merely because related hardware appears in inventory.

## Versions and permissions

| Function | Minimum documented Access version | Scopes |
| --- | --- | --- |
| Base developer API and discovery | 1.9.1 | `view:space`, `view:device` |
| Door unlock and gate commands | Consult installed API for gate support | `edit:space` |
| Temporary lock rules | 1.24.6 | `view:space`, `edit:space` |
| Reader settings | 3.3.10 | `view:device`, `edit:device` |
| Doorbell trigger/cancel | 4.0.10 | `edit:device` |
| WebSocket notifications | 1.20.11 | `view:device` |
| Managed webhooks | 2.2.10 | `view:webhook`, `edit:webhook` |
| Schedule/temporary-unlock webhook topics | 3.3.10 | Webhook scopes |

Older versions, absent scopes, and unsupported endpoints are reported as unavailable/unsupported rather than successful actions. The API reference uses port 12445; that port is verified on the development console despite conflicting wording in Ubiquiti's setup article.

## Validation boundary

The installed hardware is one `UA-Hub-Door-Mini` and one bound door with position `none`. Hubitat runtime checks validate that installation. Reader Pro, Intercom, and Gate command/event paths use documented synthetic fixtures because those devices are not present. Implementation coverage across the documented hardware API is distinct from physical verification on every model. See [validation.md](validation.md) for the deployed evidence and remaining checks.
