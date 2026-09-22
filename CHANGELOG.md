# Changelog

## 0.1.0 — 2026-09-22

Initial community release:

- Native Hubitat integration with six child drivers and capability-based discovery.
- Native Lock/ContactSensor door devices, momentary unlock, and explicit door-rule/gate controls.
- Reader configuration commands, verified doorbell/request-to-enter buttons, and site-wide emergency controls.
- Authenticated WebSocket notifications and managed signed webhooks.
- Bounded read-only reconciliation after unlock activity to detect automatic relocking.
- HPM package and repository manifests; MIT license.

Validated on Hubitat 2.5.1.183 with a Hub Mini and one door. Other hardware functions use synthetic API fixtures. All commands are enabled without additional opt-ins; read the control-safety notes and [validation limitations](docs/validation.md) before use.
