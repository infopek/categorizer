# Detection annotation pilot

The ML-009 pilot uses the Apache-2.0 `IDEA-Research/grounding-dino-tiny` teacher at pinned revision `a2bb814dd30d776dcf7e30523b00659f4f141c71` to propose boxes from accepted source archives. The teacher is a development-only annotation aid and is not a production dependency or bundled model.

Install the optional local pilot dependency into the ignored project environment, then run:

```bash
.venv/bin/python -m pip install transformers==4.57.6
.venv/bin/python ml/detection/propose_boxes.py \
  --archive ml/artifacts/lepidoptera/Polyommatus_eros.ZIP \
  --output ml/artifacts/lepidoptera/detection-pilot
```

Open `review.html` locally and export explicit decisions. Teacher proposals remain pending until a human reviews them; they must not be consumed as training truth merely because a box was generated. Model caches, rendered images, proposals, decisions, archives, and weights remain outside Git under ignored paths.

The proposal helper uses CUDA automatically when available; pass `--device cpu` to override it.

Apply a complete export with strict asset/index validation:

```bash
.venv/bin/python ml/detection/apply_box_reviews.py \
  --proposals ml/artifacts/lepidoptera/detection-pilot-v2/proposals.json \
  --decisions ~/Downloads/detection-decisions.json \
  --reviewer repository-owner \
  --output ml/artifacts/lepidoptera/detection-pilot-v2/reviewed-annotations.json
```

Only `accepted` decisions with at least one explicitly selected proposal become training-eligible. Adjustment, multiple-subject, rejected, and no-visible-subject decisions remain in the reviewed manifest but are excluded until resolved.

## Range-based multi-species sampling

Figshare's object storage supports HTTP byte ranges. The sampler reads ZIP central directories and selected compressed members without transferring complete species archives:

```bash
.venv/bin/python ml/detection/sample_remote_archives.py \
  --output ml/artifacts/lepidoptera/detection-balanced-sample \
  --class-count 12 \
  --images-per-class 5
```

Class IDs and archive members are selected by a seeded SHA-256 rank. Every image is decoded, hashed, and recorded with its Figshare file/archive identity. The output is explicitly unreviewed and remains ignored until passed through box proposal and human review.

Generate teacher proposals directly from that verified sample:

```bash
.venv/bin/python ml/detection/propose_boxes.py \
  --sample-manifest ml/artifacts/lepidoptera/detection-balanced-sample/sample-manifest.json \
  --output ml/artifacts/lepidoptera/detection-balanced-review
```

The proposal command rechecks every local byte count and SHA-256 hash and propagates its class, source archive, member, and Figshare file identities into the review manifest.

Additional tranches can exclude already sampled classes without relying on chat state:

```bash
.venv/bin/python ml/detection/sample_remote_archives.py \
  --output ml/artifacts/lepidoptera/detection-balanced-tranche-2 \
  --class-count 40 --images-per-class 5 --seed detection-pilot-v2 \
  --exclude-manifest ml/artifacts/lepidoptera/detection-balanced-sample/sample-manifest.json \
  --exclude-class polyommatus-eros
```

The exclusion manifest identity and complete excluded class-ID set are recorded in the new sample manifest.

## Reviewed dataset materialization

Combine reviewed tranches only after every manifest has passed strict decision application:

```bash
.venv/bin/python ml/detection/build_dataset.py \
  --reviewed ml/artifacts/lepidoptera/detection-pilot-v2/reviewed-annotations.json \
  --source ml/artifacts/lepidoptera/Polyommatus_eros.ZIP \
  --reviewed ml/artifacts/lepidoptera/detection-balanced-review/reviewed-annotations.json \
  --source ml/artifacts/lepidoptera/detection-balanced-sample \
  --reviewed ml/artifacts/lepidoptera/detection-balanced-tranche-2-review/reviewed-annotations.json \
  --source ml/artifacts/lepidoptera/detection-balanced-tranche-2 \
  --output ml/artifacts/lepidoptera/detection-dataset
```

The builder revalidates source hashes and box bounds, excludes unresolved images, rejects duplicate asset IDs, materializes managed image copies, and assigns train/validation/test splits independently within every source species.

## Experimental SSDLite training

Train the proposed mobile detector against the reviewed dataset:

```bash
.venv/bin/python ml/detection/train_ssdlite.py \
  --dataset ml/artifacts/lepidoptera/detection-dataset \
  --output ml/artifacts/lepidoptera/detection-training-ssdlite
```

The trainer uses a one-class SSDLite320 MobileNetV3 Large detector, records the complete run configuration and dataset-manifest hash, selects the highest-precision validation threshold meeting a 97% recall target, and evaluates the test split at that frozen threshold. If validation cannot reach the target, it selects the maximum available recall. By default it transfers every shape-compatible tensor from TorchVision's COCO detector and randomly initializes the incompatible class-prediction tensors; pass `--initialization imagenet-backbone` to reproduce the earlier backbone-only baseline. Checkpoints and reports stay in ignored artifact storage.

This is an experimental pipeline check, not distribution approval. Its ImageNet-initialized TorchVision backbone needs a separate pretrained-weight provenance decision, and the current subject-forward dataset does not replace evaluation on cluttered photos or hard negatives.

The first 20-epoch run used dataset manifest SHA-256 `24d7795861876de94029a1761202c1f294c00525754193c5162101509fd1dd2b`. Validation selected a `0.65` confidence threshold with 80.6% recall and 84.7% precision at IoU 0.5. The untouched test split then measured 71.4% recall and 84.9% precision (F1 77.6%) at the frozen threshold. This does not meet the 90% localization-recall pilot gate; it establishes a working baseline and confirms that more varied training data and hard-negative evaluation remain necessary.

With all 464 shape-compatible COCO detector tensors transferred and only 12 class-prediction tensors randomly initialized, the otherwise identical run improved validation recall to 90.3% and precision to 88.9%. The frozen test result improved to 84.1% recall and 94.6% precision (F1 89.1%). This is a substantial improvement but still misses the test recall gate, and it does not resolve the dataset or pretrained-weight provenance gaps.

A 40-epoch comparison reduced training loss further but test recall fell to 82.5%, indicating tighter fitting rather than better generalization. After adding a fourth reviewed tranche, the dataset contains 514 images and 545 boxes across 93 source species, with 312/101/101 train/validation/test images. Its 20-epoch frozen test result reached 85.7% recall and 91.8% precision (F1 88.7%). Visual inspection showed that many failures had a confident but overly loose or shifted prediction, while others involved small or camouflaged subjects; training therefore includes mild color jitter and zoom-out augmentation in addition to horizontal flips.

Augmentation improved the maximum-F1 operating point to 87.6% test recall and 94.8% precision. The high-recall validation policy selected `0.25` confidence (97.1% validation recall and 87.6% precision); that operating point measured 91.4% test recall and 87.3% precision at IoU 0.5, passing the localization-recall pilot target. Because this test split was inspected repeatedly during pilot development, the result is evidence for the configuration rather than final qualification; a new frozen cluttered/hard-negative evaluation set remains required.

Collect that independent evaluation set from individually allowlisted Wikimedia Commons files before generating and reviewing teacher proposals:

```bash
.venv/bin/python ml/detection/collect_commons_evaluation.py \
  --output ml/artifacts/lepidoptera/detection-commons-evaluation
```

The deterministic query groups intentionally mix likely Lepidoptera photos with visually confusable flowers, bees, bark, and leaves. Query-derived expectations are not truth: every candidate still requires explicit human box/no-subject review, and the set must remain frozen afterward.
