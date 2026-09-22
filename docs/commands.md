# Hardware commands and automation

Hubitat child devices expose custom commands for the documented Access operations. Use a device's command buttons or Rule Machine custom actions. Discovery imports all devices, and all commands are enabled without opt-in checkboxes. Each command still checks the target device and input values. Enabling commands never executes them during discovery.

## Door devices

| Command | Access behavior |
| --- | --- |
| `unlock()` | Momentary remote unlock using the door's configured behavior. |
| `lock()` | Native Hubitat Lock command; delegates to `lockNow()`, ending active scheduled/temporary unlocking. |
| `keepLocked()` | Apply the temporary `keep_lock` override. |
| `keepUnlocked()` | Apply the temporary `keep_unlock` override. |
| `unlockForMinutes(minutes)` | Apply a custom temporary unlock interval. |
| `resetTemporaryRule()` | Reset the temporary override. |
| `endUnlockScheduleEarly()` | End the active unlock schedule early. |
| `lockNow()` | Terminate an unlock schedule and temporary unlock. |
| `gateOpen()`, `gateClose()`, `gateStop()` | Explicit gate-controller action attempts; the API determines support. |
| `gateOpenIn()`, `gateOpenOut()` | Gate-controller entry/exit direction for double-driveway operation. |
| `refresh()` | Request current door and lock-rule observations. |

Unlock, door rules, gate actions, and doorbell commands need no additional enable preference. Rule commands intentionally expose their schedule effects rather than pretending that persistent override operations are a normal momentary lock.

`lockRelay` and `contact` are independent observations. A successful command sets `lastCommandStatus` to `accepted`; later API state determines relay and contact events. An ambiguous timeout sets `indeterminate` and does not retry the command. `lockRuleStatus` describes the result of the read-only lock-rule probe; gate support is not assumed.

## Access hardware devices

| Command | Access behavior |
| --- | --- |
| `setAccessMethod(method, enabled)` | Toggle an access method returned by this device's settings endpoint. |
| `setPinShuffle(enabled)` | Change keypad digit shuffling where supported and PIN access is enabled. |
| `setFaceSensitivity(antiSpoofing, distance)` | Change supported face-unlock sensitivity settings. |
| `ringDoorbell(roomName)` | Trigger Intercom/Reader Pro ringing, optionally naming an Intercom room. |
| `cancelDoorbell()` | Cancel ringing on supported hardware. |
| `refresh()` | Request device state and supported access-method settings. |

The API's method names are `nfc`, `bt_tap`, `bt_button`, `bt_shake`, `mobile_wave`, `pin_code`, `face`, `wave`, `qr_code`, and `touch_pass`; a method is usable only when returned for the particular device. These controls configure methods, not users, PIN values, cards, or face enrollments.

Reader and lock-rule support comes from read-only API probes rather than model names. The API has no safe capability-query operation for gate movement or doorbell triggering: support remains unknown until API evidence is available, but this does not block an explicitly invoked command. An authenticated incoming notification creates a doorbell button child independently of trigger-command support.

The reference's concrete examples use `yes`/`no` for boolean settings while its field descriptions say `true`/`false`. The integration normalizes these forms and sends the example encoding. Face sensitivity supports the combinations `no/far`, `no/medium`, `medium/near`, and `high/near` specified in the reference's explicit constraint note.

## Emergency controller

`startLockdown()`, `startEvacuation()`, and `clearEmergency()` operate on the whole Access installation and are enabled without a separate preference. The controller child reports observed `lockdown` and `evacuation` state; these are not per-door lock commands.

## Device availability

Commands fail without sending when the integration lacks fresh state, the target is excluded/offline, a required method is absent from its settings response, or arguments are invalid. Credentials still need the corresponding API scopes. A method being present in the Hubitat driver does not mean every physical model supports it.

Device-specific controls are implemented against the [official Access API reference, sections 7–8](https://assets.identity.ui.com/unifi-access/api_reference.pdf). Hardware not present in the development installation is validated with synthetic API fixtures, not represented as physically tested.
