# Lepidoptera subject-detection proposal

Status: proposed implementation amendment, 2026-07-17.

## Problem

The qualified MaxViT-T model is a species classifier. Its upstream evaluation transform resizes and center-crops the entire image, which assumes the subject already occupies a useful portion of the frame. Casual camera/gallery photos may contain a small, off-center butterfly or moth among leaves, flowers, people, or other background objects.

## Proposed pipeline

1. Run a lightweight one-class `Lepidoptera` detector on the source image.
2. Expand each accepted bounding box by a documented margin and clamp it to the image.
3. Apply the classifier's existing resize, center-crop, and normalization to each crop.
4. Classify the selected crop with the qualified 163-class MaxViT-T model.
5. When detection is absent, weak, or ambiguous, let the user adjust or select a crop instead of forcing or blocking recognition.

Detection changes localization only. It does not change the accepted 163-class catalog, scientific labels, classifier outputs, or local-only privacy boundary.

The MVP recognition journey targets one user-chosen subject. Failing to localize the only visible
butterfly or moth is therefore the critical product failure. In a multi-subject photo, detecting at
least one usable individual crop can still serve the journey; incomplete instance coverage remains
reported and useful for general detector quality, but has lower MVP severity than a single-subject
miss. The application should present multiple reliable individual boxes for selection when
available and must not treat one group-enclosing box as several subjects.

## Candidate implementation

The production pilot should fine-tune TorchVision `ssdlite320_mobilenet_v3_large` as a one-foreground-class detector. The official architecture has 3,440,060 parameters and its reference checkpoint is approximately 13.4 MiB. TorchVision is already part of the pinned ML stack, so this choice avoids a new production training framework. Stock COCO weights cannot be used as the finished detector because COCO has no butterfly or moth category.

For dataset preparation only, Grounding DINO can propose `butterfly` and `moth` boxes. SAM 2 may refine questionable boxes where a mask-derived tight box materially helps review. Both official projects publish Apache-2.0-licensed code and checkpoints. Neither teacher belongs in the application or release bundle.

Sources:

- [TorchVision SSDLite implementation and weight metadata](https://docs.pytorch.org/vision/main/_modules/torchvision/models/detection/ssdlite.html)
- [Official Grounding DINO repository and license](https://github.com/IDEA-Research/GroundingDINO)
- [Official SAM 2 repository and license](https://github.com/facebookresearch/sam2)

## Data and review policy

- Draw a deterministic, species-balanced pilot subset from the accepted CC BY 4.0 Figshare source.
- Preserve image, archive, source-class, teacher-checkpoint, prompt, threshold, and generated-box identities.
- Review generated boxes before they become training truth. Review must support accept, adjust, reject, multiple-subject, and no-visible-subject outcomes.
- Freeze a separate, manually reviewed messy-photo localization test set. Do not select thresholds on that test set.
- Include hard negatives and multi-subject examples; a centered single-subject subset alone is insufficient evidence.
- Generated annotations and source images remain outside Git; manifests, schemas, tools, and aggregate reports may be committed.

## Pilot gates

The pilot is accepted for integration only when all of the following are reported:

- localization recall at IoU 0.5 on the frozen reviewed test set;
- false positives per image and no-detection rate, including hard negatives;
- results split by small, medium, and large subject area and by single/multiple subjects;
- PyTorch/ONNX numeric and box/ranking equivalence;
- Android detector-only and detector-plus-classifier latency on the accepted Galaxy S20 FE;
- detector, combined model, APK/download, installed-footprint, and peak-memory measurements against accepted budgets;
- crop-versus-whole-image classifier accuracy on a reviewed end-to-end set.

Initial evaluation targets are at least 90% localization recall at IoU 0.5 and median detector-plus-classifier inference below the existing two-second gate. These are pilot targets, not permission to weaken the accepted classifier accuracy or release resource gates.

## Rejection and fallback

Reject or revise the detector if it harms end-to-end identification, cannot pass ONNX/Android compatibility, or pushes the combined application over an accepted hard resource gate. Manual crop selection remains the no-detector fallback and should be implemented independently of pilot success.

## MVP disposition

The v5 512-pixel SSDLite candidate is the strongest experimental detector, including 94.1% recall
for the intended single-subject slice and substantially improved hard-negative behavior. Its ONNX
candidate is 8.6 MiB and matches PyTorch within 0.000183 absolute error on five fixtures. It is not
distribution-approved because its TorchVision COCO initialization lacks an artifact-specific
commercial-use provenance grant. A random-initialized replacement trained only on approved data
failed precision catastrophically. The distributable MVP therefore retains manual crop selection
and does not bundle automatic detection; detector work is postponed pending an approved pretrained
weight source or a substantially larger clean pretraining effort.
