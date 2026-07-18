# ONNX candidate export and equivalence

The exporter uses PyTorch's recommended `dynamo=True` path and fixed NCHW batch-one input. ONNX Script is pinned because the modern exporter requires it. Candidate bundles are generated under ignored `ml/artifacts/` and must not enter Git until all release gates approve them.

```bash
.venv/bin/python ml/export/export_bundle.py --checkpoint ml/runs/candidate/checkpoint.pt --run-metadata ml/runs/candidate/run.json --evaluation ml/runs/candidate/held-out-report.json --manifest ml/datasets/manifest.json --splits ml/datasets/splits.json --root /local/images --output ml/artifacts/candidate
```

The command verifies all upstream identities, exports `model.onnx`, loads it with pinned ONNX Runtime CPU, and emits `class-map.json`, `model-manifest.json`, a license notice, and `equivalence-report.json`. It fails before producing an acceptable bundle if fixed shapes, hashes, operators, numeric tolerances, full rankings, or held-out metrics disagree. The random-initialized fixture candidate remains experimental and not approved for distribution.

## Pinned Lepidoptera checkpoint

The upstream 163-class MaxViT-T checkpoint has a separate fail-closed exporter because it is a raw TorchVision state dictionary rather than a Categorizer training-run checkpoint:

```bash
.venv/bin/python ml/export/export_lepidoptera_checkpoint.py \
  --checkpoint ml/artifacts/lepidoptera/pytorch_model.bin \
  --class-map ml/catalog/lepidoptera-checkpoint-class-map.json \
  --sample-archive ml/artifacts/lepidoptera/Polyommatus_eros.ZIP \
  --output ml/artifacts/lepidoptera/onnx-bundle
```

Generated binaries and reports remain under the ignored `ml/artifacts/` directory. The exporter checks the pinned checkpoint identity, strict state-dictionary loading, fixed input/output contract, ONNX validity, CPU ONNX Runtime loading, size gate, and PyTorch/ONNX equivalence on deterministic synthetic fixtures and every image in the optional sample archive. This is only fixture-level evidence: the candidate remains unapproved until held-out evaluation can be reproduced from the upstream test split.

The exporter also emits the Android runtime `class-map.json`, `model-manifest.json`, and candidate notice. To install an experimental debug build, stage those four files under an ignored `recognition/` asset directory and pass its absolute parent:

```bash
./gradlew :apps:androidApp:installDebug \
  -PrecognitionAssetRoot="$(realpath ml/artifacts/lepidoptera/android-assets)"
```

The property is opt-in so ordinary source builds do not silently distribute an unqualified 120 MiB model.
