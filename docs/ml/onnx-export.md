# ONNX candidate export and equivalence

The exporter uses PyTorch's recommended `dynamo=True` path and fixed NCHW batch-one input. ONNX Script is pinned because the modern exporter requires it. Candidate bundles are generated under ignored `ml/artifacts/` and must not enter Git until all release gates approve them.

```bash
.venv/bin/python ml/export/export_bundle.py --checkpoint ml/runs/candidate/checkpoint.pt --run-metadata ml/runs/candidate/run.json --evaluation ml/runs/candidate/held-out-report.json --manifest ml/datasets/manifest.json --splits ml/datasets/splits.json --root /local/images --output ml/artifacts/candidate
```

The command verifies all upstream identities, exports `model.onnx`, loads it with pinned ONNX Runtime CPU, and emits `class-map.json`, `model-manifest.json`, a license notice, and `equivalence-report.json`. It fails before producing an acceptable bundle if fixed shapes, hashes, operators, numeric tolerances, full rankings, or held-out metrics disagree. The random-initialized fixture candidate remains experimental and not approved for distribution.
