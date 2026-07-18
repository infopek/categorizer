# Airplane-mode application verification

Status: passed.

On 2026-07-18, the bundled debug application was exercised on the accepted Samsung
Galaxy S20 FE with Android airplane mode enabled through `adb shell cmd connectivity
airplane-mode enable`. The prior network state was restored after the run and airplane
mode was confirmed disabled.

The corrected core run passed 19 of 19 device tests. It included the real bundled
float32 model and 100 inference requests, archive export/import services, repository
persistence, managed-image handling, and album editing. Warm inference median was
192.78 ms, below the accepted 2,000 ms gate. An additional UI run produced 18 passes
and one accessibility-visibility flake; three command selection errors in that first
run were caused by incorrect test package names and were corrected by the passing run.

`aapt2 dump permissions` reports no `INTERNET` or `ACCESS_NETWORK_STATE` permission.
Consequently, the application cannot open ordinary network sockets or inspect network
state; recognition is not capable of falling back to a remote service.

The machine-readable evidence is
`verification/device/results/2026-07-18-airplane-mode.json`.

The repository owner then completed the parts controlled by Android system UI while
airplane mode remained enabled: gallery selection, local recognition and confirmation,
saving, application restart with persistence, and document-picker backup export. All
steps passed. Together with the automated archive import coverage, this satisfies the
complete offline application journey required by VER-005.
