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
