# Native Hubitat integrations

Use native capabilities wherever the Access API supplies matching operations and authoritative state. A custom command alone is not discoverable as a lock or switch by integrations that select devices by capability. Native capability support enables selection, but each integration still decides which capability types it exports.

## Door locks and HomeKit

The door implements Hubitat `Lock`, alongside `ContactSensor`, `Refresh`, `Sensor`, and `Actuator`:

- `unlock()` requests momentary release using Access's configured behavior, not a persistent unlock override.
- `lock()` delegates to `lockNow()`, ending active scheduled/temporary unlocking. It is not a permanent keep-locked override.
- Native `lock` follows observed relay state (`locked`/`unlocked`); unavailable observations produce `unknown`. Command acceptance does not fabricate a lock-state event.
- `contact` describes physical DPS position separately. A locked relay does not prove the door is closed or mechanically secured.

In Hubitat, open **Apps → HomeKit Bridge**, select the door under locks, and save. If already exported as a contact sensor, use **Show accessory classes and characteristics** to inspect/select its lock representation; reload the bridge if needed. If the bridge is not paired, scan its QR code with Apple Home's **Add Accessory**. An Apple home hub is needed for away-from-home access. See [Hubitat's HomeKit Bridge documentation](https://docs2.hubitat.com/en/apps/homekit-integration).

## Other capability mappings

- Physical door position: `ContactSensor`; no fabricated closed state when DPS is missing.
- Verified doorbell and request-to-enter notifications: `PushableButton`, button 1, for native button automations.
- Hardware settings and commands: `Actuator`; refreshable resources: `Refresh`. These markers do not themselves provide HomeKit tiles.
- Reader authentication methods are installation-time configuration, not everyday switch devices. Their existing explicit configuration commands remain available without adding switch children.

## Operations without an exact mapping

Temporary lock-rule variants, PIN shuffling, face-sensitivity pairs, and intercom room selection remain custom commands. Reader ringing starts a call, not a selectable audio track, so it is not advertised as `Chime`.

Site-wide lockdown and evacuation are not an alarm siren/strobe. Independent switch representations would also make turning one off ambiguous because the API's clear action clears both modes. Keep their explicit emergency commands.

Gate movement has no safe read-only capability probe. Applying `GarageDoorControl` to every door would falsely advertise an actuator on ordinary doors; accepting an open/close request does not prove gate position. Keep explicit gate commands rather than guess from model names or invent position feedback.

The [official Hubitat capability list](https://docs2.hubitat.com/en/developer/driver/capability-list) defines the native command and attribute contracts. `LockCodes` is not exposed: credential administration is outside this integration's scope.
