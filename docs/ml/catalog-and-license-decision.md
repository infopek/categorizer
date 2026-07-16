# MVP Catalog and Training-Asset Decision

Status: accepted by the repository owner on 2026-07-13 under `DEC-001`.

This is an engineering provenance decision, not legal advice. Every individual asset still requires verification before use.

## Proposed catalog

Use `ml/catalog/mvp-car-catalog.json` as the stable MVP class map:

- 150 classes across 25 makes;
- stable lowercase class IDs;
- make/model labels by default;
- explicit generation classes for visually important BMW, Mercedes-Benz, Porsche 911, Corvette, Defender, and first-generation Tesla Roadster distinctions;
- no trim-level classes.

Catalog changes after acceptance require a reviewed version change because class order and IDs become part of the model/application contract.

## Source decision

| Source | Published terms | Decision |
|---|---|---|
| Wikimedia Commons | Files are generally under individual free licenses or public domain. Each file can have different attribution, license-link, and share-alike requirements; Wikimedia tells reusers to verify every file. | Conditionally allowed only for files individually verified as public domain, CC0, or CC BY 2.0/3.0/4.0. Record file page, author, exact license, license URL, source URL, retrieval date, and content hash. Exclude ShareAlike and unclear files from the initial pipeline to reduce derived-work ambiguity. |
| Open Images V6 | Google publishes annotations under CC BY 4.0 and lists images as CC BY 2.0, but explicitly gives no warranty and instructs users to verify each image's license. Labels are broad and do not provide a reliable 150-model catalog. | Conditionally allowed for verified generic-car/background/negative examples and CC BY annotations. Do not treat Open Images labels as make/model ground truth; verify each selected image and preserve attribution/provenance. |
| CompCars | The official dataset page restricts it to non-commercial research, prohibits commercial exploitation, and says the images are obtained from the internet and are not owned by the publisher. | Rejected for training, evaluation, redistribution, and derived release artifacts. It may be referenced academically but not downloaded into this project workflow. |
| Stanford Cars | No current first-party artifact-specific commercial license was established during this review. Mirrors or dataset-loader metadata do not create permission. | Rejected pending a verifiable first-party license granting the intended training and distribution rights. |

Primary source links:

- Open Images license section: https://storage.googleapis.com/openimages/web/factsfigures.html#licenses
- Wikimedia Commons reuse guidance: https://commons.wikimedia.org/wiki/Commons:Reusing_content_outside_Wikimedia
- CompCars official terms: https://mmlab.ie.cuhk.edu.hk/datasets/comp_cars/

## Pretrained-weight decision

TorchVision source code is BSD-3-Clause, but its model/weight documentation does not state a separate artifact license for downloaded pretrained weights. A software-repository license is not sufficient evidence that the training data or every weight artifact carries the same rights.

Therefore:

- TorchVision architecture code is allowed subject to its BSD-3-Clause notice.
- No pretrained weight is approved by this decision.
- The initial compliant baseline must use random initialization, or a later weight may be admitted only after an artifact-specific license/provenance record is reviewed.
- ImageNet/third-party pretrained weights remain excluded until that record exists.

### Accepted amendment: one pinned `timm` artifact

On 2026-07-16 the repository owner accepted the exact
`timm/mobilenetv3_small_100.lamb_in1k` `model.safetensors` artifact recorded in
`docs/ml/pretrained-weight-artifact-proposal.md`. That artifact alone is conditionally
approved for transfer-learning initialization under its recorded Apache-2.0 model
license, immutable revision, SHA-256 verification, provenance disclosure, notice, and
full evaluation requirements. This amendment does not approve TorchVision weights,
other `timm` artifacts, or ImageNet assets generally.

Primary source links:

- TorchVision BSD-3-Clause license: https://github.com/pytorch/vision/blob/main/LICENSE
- TorchVision model/weight documentation: https://docs.pytorch.org/vision/stable/models.html

## Required per-image manifest

Before `ML-002` may consume an image, its local manifest record must include:

- stable asset ID and class ID;
- original file/source URL and file-description URL;
- author/rightsholder;
- exact license identifier and license URL;
- attribution text;
- retrieval timestamp and content hash;
- label reviewer and review status;
- duplicate group and split group;
- exclusion reason when rejected.

No raw images, access credentials, private URLs, or unapproved weights belong in Git.

## Acceptance

The repository owner accepted both the 150-class catalog and this engineering source policy on 2026-07-13. This acceptance does not waive per-asset checks or replace legal review where commercial distribution risk is material.
