# Reproducible Python ML environment

ML tooling supports CPython 3.12 and has a CPU-only verification baseline. CUDA is optional and must
use a separately reviewed lock; it is never required for dataset, export, or smoke verification.

## Clean setup

From the repository root:

```bash
python3.12 -m venv .venv
.venv/bin/python -m pip install --upgrade pip
.venv/bin/python -m pip install -r ml/requirements-dev.lock
.venv/bin/python ml/smoke_test.py
.venv/bin/python -m pip check
```

The lock includes the PyTorch CPU wheel index and fully pinned transitive dependency versions. Unsupported
Python versions fail the smoke test explicitly. Platform-specific wheel availability is resolved when
the lock is regenerated and must be verified on each supported OS before adding that OS.

## Updating dependencies

Install `pip-tools==7.5.3` in a disposable Python 3.12 environment, then run:

```bash
pip-compile --allow-unsafe --resolver=backtracking --output-file=ml/requirements.lock ml/requirements.in
pip-compile --allow-unsafe --resolver=backtracking --output-file=ml/requirements-dev.lock ml/requirements-dev.in
```

Review both input and lock diffs. Recreate a clean environment and rerun the smoke test and
`pip check`. Do not install CUDA packages into the baseline lock.

Datasets, caches, runs, checkpoints, exported ONNX files, and virtual environments are ignored and
must remain outside normal Git history.
