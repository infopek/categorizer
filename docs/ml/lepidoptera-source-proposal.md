# Lepidoptera MVP Dataset and Checkpoint Proposal

Status: accepted implementation decision under the 2026-07-17 MVP scope amendment.

This is an engineering provenance review, not legal advice.

## Dataset record

- Title: *Machine learning training data: over 500,000 images of butterflies and moths (Lepidoptera) with species labels*
- Figshare article ID: `29135618`
- DOI: `10.25452/figshare.plus.29135618`
- Dataset-record license: CC BY 4.0
- License URL: https://creativecommons.org/licenses/by/4.0/
- Published: 2025-07-14
- Contents: 541,677 citizen-science images collected in Austria from 2016–2023, with identifications confirmed by an experienced entomologist
- Labels: 185 species or source-defined inseparable species pairs
- Distribution: 186 species ZIP archives, approximately 315.34 GiB compressed in total

The accompanying Scientific Data article is separately published under CC BY-NC-ND
4.0. The project must cite it as documentation but must not treat the article license
as the dataset artifact license or copy article content into derived materials.

## MVP class boundary

Use the 162 classes with at least 50 source images, matching the published baseline's
eligibility rule. Preserve the four source-defined combined species-pair labels where
photographs cannot distinguish the members reliably. Scientific names are stable
class IDs; common names are optional presentation metadata.

Do not download all source archives initially. Fetch the class-count manifest, exact
checkpoint, scripts, and a deterministic evaluation sample first. Full or balanced
image acquisition is conditional on the checkpoint failing mobile gates or requiring
distillation.

## Provided checkpoint

- Figshare file ID: `55170962`
- Name: `pytorch_model.bin`
- Size: 122,783,842 bytes
- Figshare MD5: `040fea4abf59a631d41e3878f51d073c`
- Architecture: MaxViT-T fine-tuned from ImageNet-1K initialization
- Published result: 97.87% test top-one accuracy, 93.54% mean recall, and 96.31% mean precision on the source evaluation

## Pinned local verification

| Artifact | Figshare file ID | Bytes | MD5 | SHA-256 |
|---|---:|---:|---|---|
| `Images_per_species.csv` | `56259125` | 4,217 | `b36c5ad93f26d430b45d5a6131bcf5fd` | `c5214438a186fe46304a088f020dd19fbede5171de9baf55a3b66440341e3af8` |
| `Scripts_model_training.zip` | `55170923` | 12,591 | `c19df4d03583c0b8992f36b09b72d303` | `021feab78d26450a5fd6e41a2c1596ce3c8b49bd4afb87abaae8169120909ca0` |
| `pytorch_model.bin` | `55170962` | 122,783,842 | `040fea4abf59a631d41e3878f51d073c` | `ac3cf138930a8b6f52dcb064ff44ace39b701d3412812e457930463073e5eca0` |

The source CSV contains 184 rows. Exactly 162 meet the accepted minimum of 50
images; their stable, alphabetically ordered scientific-name mapping is recorded in
`ml/catalog/mvp-lepidoptera-catalog.json`. The local downloads remain ignored under
`ml/artifacts/` and are not distribution artifacts.

The checkpoint is only a candidate. Before use it requires a locally computed SHA-256,
state-dict/class-order inspection, license attribution, held-out leakage review, ONNX
export, cross-runtime equivalence, package-size measurement, and latency/memory testing
on the accepted Samsung Galaxy S20 FE. If it fails, the fallback is a smaller model
trained or distilled from a balanced, reproducibly acquired subset.

## Primary sources

- https://plus.figshare.com/articles/dataset/29135618
- https://api.figshare.com/v2/articles/29135618
- https://www.nature.com/articles/s41597-025-05708-z
- https://github.com/FriederikeBarkmann/MaxVit_ButterflyIdentification

## Approval boundary

Merging this proposal approves evaluation of the pinned dataset record and checkpoint;
it does not approve a release model before all provenance, accuracy, equivalence,
size, memory, and device-latency gates pass.
