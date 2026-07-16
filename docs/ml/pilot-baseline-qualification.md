# Pilot baseline qualification

The first full-catalog baseline is a rejected ML-007 candidate. It establishes that the
reviewed pilot dataset and random initialization are sufficient to exercise the complete
training and evaluation path, but not to qualify a bundled model.

## Candidate identity

- Dataset: 536 approved Wikimedia Commons images across 150 accepted classes
- Split: 236 training, 149 validation, and 151 held-out images
- Architecture: MobileNetV3 Small, 224-pixel input, random initialization
- Training: 20 epochs, seed 1701, pinned CPU environment
- Checkpoint state SHA-256: `bce6d8a5e4ea9a07da7b4f8836866201241d8b428469a4349c76738d9c200643`
- Checkpoint size: 20,318,769 bytes
- Manifest SHA-256: `8a33679136abc117cb295d12b14ff9fc77a70bcbda45f58e35d873bfa51ffc3f`
- Split SHA-256: `708eb598fba7deff46ece3132a37a939064733967722279658147c3806868d09`

The ignored local run metadata SHA-256 is
`808fd3e9078a47a6f30b24cfbfb832db6a825f2cf1983146e82c8e346706aa2d`; the ignored
held-out report SHA-256 is
`c931f09c3ccbc76a84000784be03e6a98f3f15db89560921cea7d9f36f59c3b7`.

## Results

| Evidence | Result |
| --- | ---: |
| Final training accuracy | 65.68% |
| Final validation accuracy | 0.67% |
| Held-out top-one accuracy | 0.66% |
| Held-out top-five accuracy | 3.31% |
| Required held-out top-five accuracy | 80.00% |
| Training time after the one-epoch pilot | 2m 0s |
| Peak training RSS | 1,184,964 KiB |

The growing train/validation gap is direct evidence of severe overfitting. Held-out
support is also only one image for almost every class, so these metrics are useful for
rejecting this candidate but are too fragile for release qualification claims.

## Decision

Reject this candidate before ONNX optimization and Android benchmarking. Quantization,
package-size work, and device latency measurements cannot repair the failed accuracy
gate. ML-007 remains blocked on a materially larger reviewed dataset or acceptance of a
specific pretrained-weight artifact whose license and provenance satisfy DEC-001.

Do not weaken the 80% gate or describe minimum three-image class coverage as a
production training threshold. The minimum was only a catalog and pipeline completeness
milestone.
