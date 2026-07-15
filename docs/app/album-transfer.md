# Album transfer

The album screen opens a transfer screen for manual ZIP backup and restore. Export uses Android's
create-document contract and import uses the open-document contract, so the app requests no broad
filesystem permission and works with any installed system document provider.

Import first copies the selected document into private cache and validates its schema version,
layout, paths, resource limits, checksums and conflicts. This preview is non-mutating. Invalid or
unreadable archives never display the confirmation action. Existing local entries are retained and
listed as conflicts; their archive counterparts are skipped only when the user explicitly confirms.

Confirmation uses the archive engine's hash- and local-revision-bound plan. A changed archive or
album is rejected as stale, while file or storage failures roll back installed images and metadata.
Completion is displayed only after the atomic metadata commit succeeds. Canceled or revoked document
access is recoverable and is reported without claiming that any data moved.

Device-test screenshots:

- `screenshots/ui-005/valid-preview.png`
- `screenshots/ui-005/invalid-archive.png`
