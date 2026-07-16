# Frozen held-out evaluation

ML-004 evaluates a candidate once against the frozen `test` assignments. It verifies the dataset, split, catalog, configuration, environment lock, training run, and checkpoint tensor identities before inference. Every catalog class must meet `--min-support` (default 1), otherwise evaluation fails instead of omitting the class.

```bash
.venv/bin/python ml/evaluation/evaluate.py --manifest ml/datasets/manifest.json --splits ml/datasets/splits.json --root /local/images --checkpoint ml/runs/candidate/checkpoint.pt --run-metadata ml/runs/candidate/run.json --output ml/runs/candidate/held-out-report.json
```

The machine-readable report contains aggregate top-one/top-five macro and weighted accuracy, sample counts, per-class support and accuracy, the complete confusion matrix, confidence calibration bins, and a failure gallery containing licensed asset IDs rather than copied images. Tied logits rank by ascending class index. For fewer than five classes, top-five uses all available classes.

Optional unsupported/OOD evidence can be supplied with `--unsupported-logits` as JSON rows containing `asset_id` and `logits`. It is reported separately and never changes the supported-class 80% top-five gate. Held-out results must not be used for tuning; changing the candidate after viewing them requires a newly versioned selection/test protocol.
