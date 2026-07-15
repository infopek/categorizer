# Licensed dataset manifests and deterministic splits

The pipeline never downloads images. An operator explicitly configures a local root and metadata file,
then `prepare_local_manifest.py` records content hashes without copying data. Raw images and generated
manifests remain under ignored `ml/datasets/` or another non-repository location.

Every usable record requires an accepted class ID, public source and description URLs, author,
approved license and URL, attribution, retrieval time, content hash, label reviewer/status, duplicate
group, and vehicle/source split group. Pending, rejected, corrupt, ambiguous, or otherwise excluded
records remain visible in the quality report. CompCars, Stanford Cars, unknown licenses, private URLs,
and labels outside the accepted catalog fail validation.

```bash
.venv/bin/python ml/dataset/prepare_local_manifest.py --metadata /local/metadata.json --root /local/images --output ml/datasets/manifest.json
.venv/bin/python ml/dataset/pipeline.py --manifest ml/datasets/manifest.json --root /local/images --output ml/datasets/splits.json --report ml/datasets/quality.json --seed categorizer-v1
```

Splits are assigned per class by stable hash of the seed and `split_group`; a group never crosses
train, validation, or test. Exact duplicates must share `duplicate_group`. Perceptual dHash distance
of four or less is reported, and near duplicates in different split groups fail before output.
The split artifact records the source-manifest SHA-256 and the quality report records its own stable
split SHA-256, per-class counts, exclusions, duplicate pairs, and split counts.
