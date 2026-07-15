#!/usr/bin/env python3
"""CPU-only PyTorch -> ONNX -> ONNX Runtime environment smoke test."""

from __future__ import annotations

import platform
import sys
import tempfile
from pathlib import Path

import numpy as np
import onnx
import onnxruntime as ort
import torch
import torchvision

SUPPORTED = (3, 12)


class TinyClassifier(torch.nn.Module):
    def forward(self, value: torch.Tensor) -> torch.Tensor:
        return torch.stack((value.sum(dim=1), value.mean(dim=1)), dim=1)


def main() -> int:
    if sys.version_info[:2] != SUPPORTED:
        print(f"ERROR Python {SUPPORTED[0]}.{SUPPORTED[1]} is required; found {platform.python_version()}", file=sys.stderr)
        return 2
    torch.manual_seed(7)
    sample = torch.arange(12, dtype=torch.float32).reshape(3, 4)
    model = TinyClassifier().eval()
    expected = model(sample).detach().numpy()
    with tempfile.TemporaryDirectory(prefix="categorizer-ml-smoke-") as directory:
        output = Path(directory) / "tiny.onnx"
        torch.onnx.export(model, sample, output, input_names=["input"], output_names=["scores"], opset_version=18, dynamo=False)
        graph = onnx.load(output)
        onnx.checker.check_model(graph)
        session = ort.InferenceSession(output.as_posix(), providers=["CPUExecutionProvider"])
        actual = session.run(["scores"], {"input": sample.numpy()})[0]
    np.testing.assert_allclose(actual, expected, rtol=1e-6, atol=1e-6)
    print(f"RESULT OK python={platform.python_version()} torch={torch.__version__} torchvision={torchvision.__version__} onnx={onnx.__version__} onnxruntime={ort.__version__} provider=CPUExecutionProvider max_abs_diff={np.max(np.abs(actual - expected)):.8f}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
