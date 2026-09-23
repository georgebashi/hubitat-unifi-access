# Validation record

## Initial release — 2026-09-22

- Tested with Hubitat 2.5.1.183 and UniFi Access on a UCG Fiber. Hardware validation covers one bound door and a `UA-Hub-Door-Mini` without a door position sensor; other models use documented synthetic fixtures.
- Authenticated read-only door and device requests succeeded on port 12445. Nested device inventory groups are normalized. Private identifiers and responses remain outside source control.
- The app and all six drivers compile on Hubitat. Live metadata categorizes the app under Integrations.
- Live polling verifies availability and relay observations without inventing contact state when DPS is absent. The native Lock capability publishes observed `locked`/`unlocked`, or `unknown` when unavailable.
- Read-only capability probes correctly treat the Mini's empty access-method settings as unsupported. Lock-rule reads succeed. Gate and doorbell action support cannot be safely probed, so explicitly invoked commands may attempt those operations without a model allowlist.
- Emergency state reads, authenticated WebSocket connection, and managed signed webhook registration were verified. Doorbell and request-to-enter children are created only after verified incoming events.
- Live webhook validation with an unknown event and a UTF-8 body returned HTTP 200 for a valid signature, and HTTP 401 for invalid, expired, and replayed signatures. These validation requests did not synthesize physical device events.
- All commands are enabled without opt-in checkboxes, including site-wide emergency controls. No physical control commands were issued by automated deployment or validation.
- All 20 offline regression groups pass, including native capabilities, API failures, authenticated event routing, stale callbacks, missing devices, and bounded external-unlock/auto-relock reconciliation.
- The developer received a user report that Hubitat-originated control works, but UniFi-originated auto-relock updates were delayed. The release adds bounded read-only reconciliation, validated with fixtures and successful live deployment; an end-to-end physical retest is still needed.

Offline tests execute production Groovy methods with Hubitat bindings stubbed. Run `sh tests/run.sh`. Passing offline tests do not establish live scheduling, TLS, event persistence, physical actuation, or compatibility with every model.

## Remaining validation

Reader, Intercom, Gate, and request-to-enter paths are fixture-tested, not physically verified. Controller outage recovery on the hub, large-installation resilience, supervised control/state verification, and clean HPM installation/match-up remain release-validation work. HomeKit pairing and remote operation require user verification.

## Monitoring recovery repair — 2026-09-23

- Investigated unavailable commands with no scheduled app jobs after a hub restart. The original periodic monitoring used chained one-shot jobs and had no startup subscription. This is consistent with a missed one-shot execution during downtime; the precise scheduler loss mechanism is not independently proven.
- Periodic polling and freshness checks now use recurring cron schedules. A `systemStart` subscription reinitializes monitoring, invalidates old callbacks, and clears pending commands without replaying them.
- Initialization and connection-failure handling exclude the event-stream child from hardware `markStale()` calls; that driver does not implement the hardware health interface. The old generic child stub concealed this error.
- Added synthetic coverage using the production event driver for all four polling intervals, startup recovery, obsolete callbacks, missing credentials, and absence of physical command requests. All 21 offline regression groups pass.
- Corrected parent app compiled and activated on Hubitat. Fresh door/device reads, recurring jobs, startup subscription, and active webhook were verified. A subsequent scheduled poll advanced both read timestamps, both monitoring jobs recorded executions and retained their next runs, and the door reported online health. No physical command was sent. An actual reboot and physical control retest remain unverified.

Scheduling references: Hubitat [Common Methods](https://docs2.hubitat.com/en/developer/common-methods-object) and [App Object](https://docs2.hubitat.com/en/developer/app-object). Hubitat's [Rule Machine documentation](https://docs2.hubitat.com/apps/rule-machine/rule-5-1) also identifies `systemStart` as a location event. Cron registration and subscription were verified on the target hub; restart behavior still requires an actual reboot test.
