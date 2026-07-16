# Pretrained pilot qualification

The DEC-001-approved `timm` transfer baseline is a rejected ML-007 candidate. It is a
material improvement over random initialization, but it does not approach the release
accuracy gate on the 536-image pilot dataset.

| Evidence | Random initialization | Pretrained transfer |
| --- | ---: | ---: |
| Training accuracy after 20 epochs | 65.68% | 100.00% |
| Validation accuracy after 20 epochs | 0.67% | 12.75% |
| Held-out top-one accuracy | 0.66% | 7.95% |
| Held-out top-five accuracy | 3.31% | 13.25% |
| Required held-out top-five accuracy | 80.00% | 80.00% |

The transfer run used 236 training, 149 validation, and 151 held-out images; seed 1701;
20 epochs; and the pinned CPU environment. Its checkpoint state SHA-256 is
`fc064857d95e2d24f7df81c5cfbffa430d64d1950451585e47f7588b76a56e9d`.

Reject this candidate before ONNX optimization and device benchmarking. The result
shows that pretrained features help, but the tiny per-class sample count still causes
complete training-set memorization and weak generalization. ML-007 remains blocked on a
materially larger, diverse, reviewed dataset. The 80% gate remains unchanged.
