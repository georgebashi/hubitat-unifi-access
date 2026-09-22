# Integration plan

## Target and milestones

Target: support physical UniFi Access devices in Hubitat, with all discovered devices exposed by default. Include available device state, events, and controls; user/credential/policy/schedule/visitor administration is outside scope. The first milestone targets the installed hardware; broader hardware support is tracked in [resource-mapping.md](resource-mapping.md).

1. **Foundation and discovery:** native Hubitat app and child drivers, console authentication, inventory discovery, synthetic contract tests, and live read-only verification. Development environment: Hubitat and UCG Fiber running Access. Authenticated GET of doors and devices on port 12445 succeeded on 2026-09-22. Validated hardware: one bound door and one `UA-Hub-Door-Mini`, without a position sensor. Device data is grouped in nested arrays.
2. **Installed hardware:** all doors imported by default, relay and physical-position reporting, health/freshness, manual refresh, and momentary unlock. Add other installed device kinds after reading inventory. Validate on the real Hubitat runtime and perform a separately coordinated physical command test.
3. **Broader hardware:** capability-driven support for Access hubs, readers, doorbells, and other documented hardware functions. Use read-only settings/lock-rule probes and reported metadata, never product-name allowlists. Write-only gate and ringing functions allow explicit command attempts without claiming support; discovery must not actuate hardware. Record unavailable API functions honestly.
4. **Events and hardware controls:** authenticated webhook callbacks, documented device notifications, doorbell events, and supported hardware actions. Preserve periodic state reconciliation. Explicit door override controls may be offered with their schedule effects clearly stated; general schedule administration stays outside scope.
5. **Release:** hardware coverage ledger, regression tests, install/update/uninstall validation, stable source hosting, HPM manifest and repository index, and documented scope/version compatibility.

## Architecture

One native Groovy parent app owns the Access token, HTTPS requests, resource inventory, polling, command dispatch, and child-device lifecycle. Child drivers expose Hubitat events and delegate actions to the parent. No separate server or Maker API is required for the polling milestone. Split transport/parsing from resource mapping sufficiently to test with synthetic fixtures; do not require unavailable third-party libraries in Hubitat code.

Use namespaced identifiers containing the installed app ID, resource type, and Access resource ID. Import discovered devices by default; offer exclusions as an option. Never remove missing devices automatically: mark unavailable and retain automation references. Removal must be an explicit operation. Bound inventory and event history retained in app state.

Hardware coverage means each device function has an explicit implemented, planned, unsupported-with-reason, or version-gated entry. Read and write paths both count. A generic arbitrary-URL request command is not a substitute for device support. Before final release, reconcile the matrix against the API reference from the installed Access version.

## Capability mapping rules

- Door relay `lock`/`unlock` maps to a separate custom relay-state attribute. Physical `open`/`close` maps to `ContactSensor.contact` `open`/`closed` only when observed. Missing sensors have an explicit custom unknown position and no invented contact value.
- Expose native Hubitat `Lock`: `unlock()` is momentary remote release; `lock()` invokes `lockNow()`, ending active scheduled/temporary unlocking. Native state follows observed relay state, never command acceptance. Document schedule effects prominently.
- Readers, hubs, and other physical endpoints get child devices only where their reported state/actions are meaningful. Connectivity is not presence, and a successful request is not proof of physical movement.
- Users, groups, policies, credentials, schedules, and visitors are outside scope. Avoid emitting identifying records or access credentials as public Hubitat events.
- Button/doorbell events require a verified event source. Polling logs must not invent reliable real-time button semantics.

## Command and failure behavior

Discovery is inclusive by default. Exposure does not issue commands or alter policy. All write commands are enabled without opt-in preferences; the token must still have the relevant scopes. Distinguish momentary unlock, schedule reset, persistent override, and emergency actions in labels and inputs.

Check HTTP status, Access `code`, and payload shape. Avoid overlapping requests; bound timeout and polling frequency. Reject obsolete callbacks after settings changes and old snapshots after newer reads. Apply bounded backoff for recurrent read failures and throttling in the completed transport layer. Never automatically retry a write, especially after a timeout where the result is indeterminate. Reconcile with a later read and display request acceptance separately from observed state.

Missing fields, offline devices, revoked scopes, expired tokens, and transport failures must produce honest health and freshness states. Do not turn a stale lock/contact observation into a fresh event. Avoid logging tokens, authorization headers, credential data, request bodies, raw exception messages, or raw server responses.

## Credentials and deployment

For door discovery: Access API token with `view:space`; for momentary unlock add `edit:space`; hardware discovery requires `view:device`. Additional hardware/events scopes are listed in the coverage matrix. Use only the scopes needed for your deployment. A UniFi account password, Network API key, and Hubitat Maker API token are not runtime requirements.

Store development credentials only in ignored `.secrets/`. Enter the token in the Hubitat app's password preference for runtime. The hub administrator can access app settings/backups; a password input is not a separate secret vault. Local HTTPS may use the console's self-signed certificate; make certificate-verification bypass an explicit preference and document that it weakens server authentication.

Install through Hubitat Drivers Code and Apps Code, using a hub UI login if Hub Security requires it. HPM release files need real stable source URLs; do not publish placeholder download locations. Keep package identifiers stable across releases and provide migrations for renamed drivers or changed state formats.

## Validation gates

- Offline: real production parser/dispatcher methods with Hubitat stubs; error envelopes, malformed payloads, unbound/missing doors, absent DPS, stale callbacks, commands enabled by default, and no optimistic physical state.
- Hub runtime: compile app/drivers, install and configure, verify actual async/TLS behavior and request timeout, observe read-only state against Access UI, restart/reconfigure, and recover from controller outage/token expiry.
- Hardware: with user coordination, verify momentary unlock and subsequent observed states, including schedule interactions and doors without position sensors.
- Hardware coverage: test supported device types and actions with real hardware or authoritative synthetic fixtures and record endpoint/version/scope evidence. Do not claim hardware support merely because a generic inventory record is displayed.

## Tracking

`bd ready` shows actionable tasks; epic `hua-gs1` tracks the whole integration. The local Beads Dolt database is authoritative. `.beads/issues.jsonl` is an export for review and portability, not automatic multi-machine database synchronization.
