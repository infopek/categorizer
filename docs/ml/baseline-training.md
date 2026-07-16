# Deterministic baseline training

ML-003 trains MobileNetV3 Small from random initialization. No pretrained weight is downloaded because no pretrained artifact is currently approved. The 224-pixel configuration is the dataset baseline; the 32-pixel configuration is only for pipeline verification.

Run artifacts belong under ignored `ml/runs/`, never in Git:

```bash
.venv/bin/python ml/training/train.py --manifest ml/datasets/manifest.json --splits ml/datasets/splits.json --root /local/images --baseline mobilenet_v3_small_224 --output ml/runs/mobilenet-v3-small --seed 1701
```

Resume with the same arguments plus `--resume ml/runs/mobilenet-v3-small/checkpoint.pt`; identity mismatches fail closed. `run.json` records code, environment, config, catalog, dataset and split identities, seed, transforms, hyperparameters, weight source, runtime, metrics, and a stable tensor-state hash.

Training loads only `train`; selection loads only `validation`. `test` rows are discarded before dataset construction and remain reserved for ML-004. CPU is supported. CUDA enables deterministic algorithms, though exact cross-device floating-point equality is not promised. Reduce a reviewed configuration's batch size for memory constraints and use a new output path.

Fixture verification runs `mobilenet_v3_small_fixture` twice and compares metrics and `checkpoint_state_sha256`. The accepted full dataset is not in Git and must be trained locally when its licensed manifest is available.
