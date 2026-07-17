# Lepidoptera MaxViT-T device benchmark

On 2026-07-17, the pinned 163-class MaxViT-T ONNX candidate passed the accepted warm inference-only latency gate on the Samsung Galaxy S20 FE (`SM-G780G`). The instrumentation run cycled through all 63 valid images in the pinned `Polyommatus_eros.ZIP` sample archive after applying the upstream evaluation preprocessing.

| Metric | Result |
| --- | ---: |
| Warm-up requests | 10 |
| Measured requests | 100 |
| Median inference | 202.68 ms |
| p90 inference | 207.53 ms |
| p95 inference | 209.24 ms |
| Maximum inference | 214.89 ms |
| Accepted median gate | < 2,000 ms |
| Gate result | Pass |

The device started at thermal status 0 and ended at status 1 (light); no severe throttling occurred. End-of-run total PSS was 373,888 KiB. That observation is not a complete memory-gate result because idle baseline, peak sampling, working-memory delta, and three repeated sequences were not measured.

The model therefore does not require distillation to satisfy the latency gate. At 120.72 MiB it passes the 150 MiB hard ONNX limit but misses the 80 MiB optimization target. Size optimization remains valuable, particularly if a separate subject-detection model is added before classification.

Raw samples, artifact hashes, device identity, and remaining evidence are recorded in `verification/device/results/2026-07-17-lepidoptera-maxvit-t.json`.
