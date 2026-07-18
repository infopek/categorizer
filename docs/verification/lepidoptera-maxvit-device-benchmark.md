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

## Optimization comparison

On 2026-07-18, two weight-reduction candidates were tested without changing the
float32 input/output contract:

| Candidate | ONNX size | Fixture comparison | S20 FE result | Decision |
| --- | ---: | --- | --- | --- |
| Float32 baseline | 120.72 MiB | Reference | 202.68 ms median | Retained |
| Dynamic INT8 weights | 31.90 MiB | 98.48% top-one agreement; 74.24% top-five-set agreement across 66 fixtures | 293.35 ms median; 232,340 KiB end PSS | Held-out qualification required |
| Float16 weights | 59.54 MiB | 100% top-one agreement; 96.97% top-five-set agreement across 66 fixtures | Android CPU provider rejected FP16 `Gelu` | Rejected as incompatible |

Dynamic INT8 passes the size, latency, and observed-memory gates, but its top-five
drift is material. The available 66-image archive covers only one source species and
cannot substitute for the required per-class held-out evaluation. It is therefore an
optimization candidate, not the bundled release candidate. The reproducible exporter
supports `--dynamic-int8` and `--float16`; both modes remain fail-visible in their
reports as requiring held-out acceptance.

The exact INT8 host comparison is recorded in
`verification/device/results/2026-07-18-lepidoptera-maxvit-int8-comparison.json`.
The Android instrumentation benchmark now reads the same
`recognition/model.onnx` asset used by the application, preventing the benchmark from
silently measuring a separately staged model.

Raw samples, artifact hashes, device identity, and remaining evidence are recorded in `verification/device/results/2026-07-17-lepidoptera-maxvit-t.json`.
