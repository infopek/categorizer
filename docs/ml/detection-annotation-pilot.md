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
