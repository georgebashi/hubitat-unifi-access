# Hubitat UniFi Access

Native Hubitat integration for physical UniFi Access hardware: discovery, monitoring, door and gate controls, supported reader settings, doorbells, and emergency status. User, credential, and access-policy administration is outside scope. See the [command reference](docs/commands.md) and [hardware coverage](docs/resource-mapping.md) for supported operations and model constraints.

Early release, validated on Hubitat 2.5.1.183 with one door and a UniFi Access Hub Mini. Other hardware paths use documented synthetic fixtures, not physical verification. See [validation evidence](docs/validation.md).

## Current package

- `apps/UnifiAccess.groovy`: parent app, token, discovery, polling, child lifecycle, and command dispatch.
- `drivers/UnifiAccessDoor.groovy`: relay state, optional physical contact, health, refresh, and momentary `unlock`.
- `drivers/UnifiAccessDevice.groovy`: device model, connectivity/adoption, health, and refresh.
- `drivers/UnifiAccessController.groovy`: site-wide emergency status and explicit emergency commands.
- `drivers/UnifiAccessDoorbell.groovy`: one-button doorbell events for supported reader/Intercom hardware.
- `drivers/UnifiAccessEvents.groovy`: authenticated WebSocket notifications and connection health.
- `drivers/UnifiAccessRequestToEnter.groovy`: a door's request-to-enter button, created on its first verified event.

All discovered doors and hardware are imported by default. Existing children are retained if a device disappears. Generic hardware inventory support does not imply that every model-specific control is implemented.

Capabilities come from read-only API probes and reported metadata, not a product registry. Gate movement and doorbell triggering have no documented read-only support probe; explicit commands may attempt these operations without claiming support in advance. Discovery never actuates hardware. Receiving a verified doorbell event discovers its button automatically.

A door's availability reflects fresh API state and any known associated hub's connectivity. Missing associations remain unverified, while explicit commands can target a fresh, API-confirmed bound door. A timeout does not turn into a successful unlock. Polling defaults to 60 seconds. Signed webhook delivery supplies contact changes between polls; polling reconciles state after missed events.

The door exposes the standard `Lock` capability: `unlock()` releases momentarily using Access's configured behavior; `lock()` invokes `lockNow()`, ending active scheduled/temporary unlocking. The native `lock` attribute follows observed relay state and becomes `unknown` when unavailable. It does not prove the door is physically shut. `contact` is emitted only for a reported physical open/closed position. Missing DPS does not mean closed. On stale data, standard contact keeps its last observation; use `healthStatus` and `doorPosition` when assessing freshness. `lastCommandStatus=accepted` means the API accepted a command, not that the door physically moved.

## Install

For HomeKit lock setup and the native capability mapping, see [native integrations](docs/native-capabilities.md).

### Hubitat Package Manager

In HPM, choose **Install → From a URL** and enter:

```text
https://raw.githubusercontent.com/georgebashi/hubitat-unifi-access/main/packageManifest.json
```

Existing manual installations should use **Match Up** rather than create duplicate code or devices. See [HPM installation and publishing](docs/hpm-publishing.md) for custom-repository setup and updates.

### Manual installation

1. In Hubitat **Drivers Code**, add and save all driver files.
2. In **Apps Code**, add and save the app file.
   Enable OAuth for this app code for signed webhooks.
3. In **Apps → Add User App**, select **UniFi Access**.
4. Set the console host, API port (default `12445`), and Access bearer token. For monitoring, grant `view:space` and `view:device`; for door/gate commands add `edit:space`; for reader/doorbell commands add `edit:device`. Managed webhooks require `view:webhook` and `edit:webhook`.
5. If the console uses an untrusted certificate, explicitly enable the corresponding preference. This disables certificate verification for these requests.
6. Save the app and allow discovery to finish. All commands and event transports are enabled without additional opt-in checkboxes. Commands still validate arguments, available capability evidence, freshness, and connectivity; the token must have the required scopes.
7. Signed local webhooks register automatically. The console must reach the hub's generated local endpoint; see [event transport](docs/events.md).

Maker API and a UniFi account password are not needed. A Hubitat UI login is needed only if Hub Security requires it.

**Control safety:** all commands are available immediately after setup, subject to token permissions. `lock()` ends active scheduled/temporary unlocking; `unlock()` releases momentarily. Emergency commands affect the entire Access installation. Restrict who can access Hubitat and its automations, use only the API scopes you need, and test with someone at the door before relying on remote control. Discovery never actuates hardware. No integration can certify that a relay indication means a door is physically secured.

## License

[MIT](LICENSE). This community project is not affiliated with or endorsed by Ubiquiti or Hubitat.

## Development and verification

Development uses jj with colocated Git and Beads with a local Dolt database. Issue exports and interaction logs remain local and ignored; run `bd export -o .beads/issues.jsonl` for a local snapshot. Credentials and private discovery responses stay in ignored `.secrets/`.

Run `sh tests/run.sh` for offline production-code checks with Hubitat stubs. It downloads Groovy dependencies from Maven Central into ignored `.tools/` on first use. These checks complement, but do not replace, compilation and behavior checks on Hubitat.

For read-only developer inventory, put the bearer token in `.secrets/access-token` and run `python3 tools/discover.py --host YOUR_CONSOLE_HOST --allow-self-signed`. Omit the final flag when the server certificate is trusted. Responses are saved privately; stdout contains counts and field names only.

The current live inventory contains one door and one `UA-Hub-Door-Mini`; the API reports position `none` for the door. See [the integration plan](docs/plan.md), [hardware coverage mapping](docs/resource-mapping.md), [Access API research](docs/unifi-access-api.md), and [Hubitat API research](docs/hubitat-api.md).
