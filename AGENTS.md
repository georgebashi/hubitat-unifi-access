# Hubitat UniFi Access

Use jj for version control. Do not create commits, branches, or publish without a user request.
Track work in Beads (`bd ready`, `bd show`, `bd update`, `bd close`). Export the issue snapshot with `bd export -o .beads/issues.jsonl` after task changes. The local Dolt database is authoritative; the export is a portable issue snapshot.

The target is support for physical UniFi Access devices in Hubitat, including their available state, events, and controls. User, credential, policy, schedule, and visitor administration is outside scope. The first milestone covers installed hardware; do not describe it as all-device support. See docs/resource-mapping.md for the coverage plan.

Never put credentials, private device inventories, access logs, PINs, QR credentials, or card identifiers into source control, fixtures, logs, or issue descriptions. Local credentials belong in ignored .secrets/. Use synthetic test fixtures.

Device discovery and status reads must not change physical state or access policy. Expose discovered resources by default, but distinguish exposure from command execution. Commands must not be retried automatically. Never infer physical contact position from relay state or report command acceptance as observed physical state.

Keep native Hubitat capability values valid. Preserve child identifiers and retain missing children with unavailable health rather than silently deleting automation references. Verify API response envelopes as well as HTTP status.

Use official API documentation and record sources and unverified assumptions in docs/. Run focused offline tests, then compile and validate on the hub before claiming live support.
