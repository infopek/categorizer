# Model information and privacy

The album links to an offline About recognition screen. `AndroidModelInfoLoader` reads bundled
`model-info.json` and the accepted `mvp-car-catalog.json`; catalog IDs and declared class counts must
match before rendering. Missing or invalid metadata produces a safe unavailable state.

The current build truthfully reports that no release model is installed. A future release version
belongs in bundled metadata rather than UI constants. The screen describes ranked suggestions,
unsupported and uncertain inputs, user confirmation, and entirely local data handling.

## Notice matrix review

| Accepted decision | Offline screen notice |
|---|---|
| Project-authored 150-class catalog | Catalog ID/count and Categorizer acknowledgement |
| TorchVision architecture allowed under BSD-3-Clause | BSD-3-Clause architecture acknowledgement |
| No pretrained weight approved | No pretrained weights are approved or bundled |
| Training images require individual provenance | Individual training assets are not bundled |

The screen makes no exact-identification claim and contains no telemetry, account, subscription,
cloud inference, or remote-policy language. Device screenshots are under `screenshots/ui-006/`.
