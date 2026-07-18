# Model information and privacy

The album links to an offline About recognition screen. `AndroidModelInfoLoader` reads bundled
`model-info.json`, the accepted `mvp-lepidoptera-catalog.json`, and the reviewed common-name map;
catalog IDs, class IDs, and declared counts must match before rendering. Missing or invalid metadata
produces a safe unavailable state.

The screen reports the experimental 163-class MaxViT-T model as installed only when its manifest
and model file are present and its model version and catalog ID match the display metadata. Builds
without those runtime assets report that the model is not installed. The screen describes ranked suggestions,
unsupported and uncertain inputs, user confirmation, and entirely local data handling.

## Notice matrix review

| Accepted decision | Offline screen notice |
|---|---|
| Austrian Lepidoptera dataset and checkpoint | CC BY 4.0 attribution to Barkmann et al. and the Figshare DOI |
| MaxVit_ButterflyIdentification scripts | MIT acknowledgement |
| TorchVision architecture | BSD-3-Clause acknowledgement |

The screen makes no exact-identification claim and contains no telemetry, account, subscription,
cloud inference, or remote-policy language. Device screenshots are under `screenshots/ui-006/`.
