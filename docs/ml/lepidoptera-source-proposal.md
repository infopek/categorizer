# Lepidoptera MVP Dataset and Checkpoint Proposal

Status: proposed implementation decision under the accepted 2026-07-17 MVP scope amendment.

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
