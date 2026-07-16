# Pretrained weight artifact proposal

Status: accepted by the repository owner on 2026-07-16 as an amendment to DEC-001.

This is an engineering provenance decision, not legal advice.

## Accepted artifact

| Field | Recorded value |
| --- | --- |
| Publisher | `timm` on Hugging Face |
| Model repository | `timm/mobilenetv3_small_100.lamb_in1k` |
| Immutable revision | `1824797e7887cbec1990e4adbd6675960a36c589` |
| Artifact | `model.safetensors` |
| Size | 10,241,912 bytes |
| SHA-256 | `46d2c063b18125884c48937afa4c49e18128869e52e8db96df48bf0a4d7ff697` |
| Published model license | Apache-2.0 |
| Training provenance | ImageNet-1K; LAMB recipe described by the model card |
| Architecture | MobileNetV3 Small, 2.5 million parameters, 224-pixel input |

Primary records:

- Model card: https://huggingface.co/timm/mobilenetv3_small_100.lamb_in1k
- Immutable files: https://huggingface.co/timm/mobilenetv3_small_100.lamb_in1k/tree/1824797e7887cbec1990e4adbd6675960a36c589
- Upstream implementation and Apache-2.0 license: https://github.com/huggingface/pytorch-image-models

## Decision

Conditionally approve only the exact safetensors artifact above as a transfer-learning
initialization source. Do not treat this as approval for other `timm`, TorchVision,
ImageNet, or third-party weights.

Required controls:

- download by immutable revision and fail closed unless the SHA-256 matches;
- keep the downloaded source artifact and all derived checkpoints outside Git;
- record the repository, revision, filename, hash, Apache-2.0 license, and ImageNet-1K
  provenance in every run and final model manifest;
- include an Apache-2.0 copy and source attribution in distributed model notices;
- describe the bundled model as fine-tuned from the recorded artifact;
- re-run the full held-out, ONNX-equivalence, size, memory, and device gates;
- reject the candidate if the 80% held-out top-five threshold is not met.

## Residual risk

The model publisher explicitly labels this artifact Apache-2.0, which supplies the
artifact-specific license missing from the TorchVision weight record reviewed in
DEC-001. The model was trained on ImageNet-1K, however, and the project has not audited
every upstream training image. Acceptance therefore relies on the publisher's artifact
license and provenance statement, not an independent audit of the upstream corpus.

The repository owner accepted this residual risk for this exact artifact on 2026-07-16.
Any broader pretrained-weight approval still requires a separate reviewed amendment.
