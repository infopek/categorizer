# Lepidoptera Checkpoint Structural Qualification

Status: structurally qualified; independent accuracy, ONNX, and device gates remain open.

## Artifact

- Figshare file ID: `55170962`
- Bytes: `122783842`
- MD5: `040fea4abf59a631d41e3878f51d073c`
- SHA-256: `ac3cf138930a8b6f52dcb064ff44ace39b701d3412812e457930463073e5eca0`
- Architecture: TorchVision `maxvit_t`
- Parameters: `30,491,080`
- Input: RGB, resize shorter edge to 224, center crop 224, ImageNet normalization
- Output: 163 logits

The checkpoint loads with `strict=True`; its final weight tensor is `[163, 512]`, and
a deterministic zero-input inference returns one finite `[1, 163]` tensor.

## Class-order reconstruction

The checkpoint does not embed labels. The supplied training code uses
`torchvision.datasets.ImageFolder`, whose class order is the lexicographic order of
source folder names. `ml/catalog/lepidoptera-checkpoint-class-map.json` records that
candidate order from the Figshare archive names and maps documented source spelling
errors to the canonical scientific names.

The source CSV omitted `Polyommatus eros`, while its Figshare archive contains 63
images and the supplied evaluation script hard-codes 163 classes. In the reconstructed
map, `Polyommatus eros` is index 136. The pinned checkpoint predicted index 136 for
55 of all 63 images in that archive, strongly corroborating both the missing class and
the inferred ordering.

This 87.3% result is not held-out accuracy: the source archive may contain images used
to train the checkpoint. It is only a class-map sanity check. Release claims still
require a frozen independent sample, per-class evaluation, and leakage analysis.

## Reproduction

```bash
.venv/bin/python ml/checkpoint/inspect_lepidoptera.py \
  --checkpoint ml/artifacts/lepidoptera/pytorch_model.bin
```

## Remaining gates

- independently sourced or source-index-faithful held-out evaluation;
- confirmation of all class indices beyond the strong ImageFolder reconstruction;
- ONNX export and PyTorch/ONNX ranked-output equivalence;
- model/package size and memory measurements;
- cold and warm inference on the Samsung Galaxy S20 FE;
- fallback distillation if any accepted mobile gate fails.
