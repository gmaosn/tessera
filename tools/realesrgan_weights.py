#!/usr/bin/env python3
"""
Converts Real-ESRGAN's realesr-animevideov3.pth (BSD-3-Clause, github.com/xinntao/Real-ESRGAN)
into editor/resources/realesr-animevideov3.bin for Tessera's Kotlin engine. Needs PyTorch.

    python tools/realesrgan_weights.py path/to/realesr-animevideov3.pth

Layout, little-endian float32, layer after layer (18 convolutions, 17 PReLUs):
    conv: weights as [ky][kx][in][out] (so that the output channels are contiguous), then bias[out]
    prelu (after every conv but the last): one slope per channel
Shapes: 3→64, 16 × 64→64, 64→48 (3 colours × 4 × 4 for the pixel shuffle).
"""
import struct, sys
from pathlib import Path
import torch

params = torch.load(sys.argv[1], map_location="cpu")["params"]
convs = sorted({int(k.split(".")[1]) for k in params if k.endswith(".weight") and params[k].dim() == 4})
out = bytearray()
for i in convs:
    w = params[f"body.{i}.weight"]            # [out][in][ky][kx]
    b = params[f"body.{i}.bias"]
    out += w.permute(2, 3, 1, 0).contiguous().numpy().astype("<f4").tobytes()
    out += b.numpy().astype("<f4").tobytes()
    slope = params.get(f"body.{i + 1}.weight")
    if slope is not None and slope.dim() == 1:
        out += slope.numpy().astype("<f4").tobytes()
path = Path("editor/resources/realesr-animevideov3.bin")
path.write_bytes(out)
print(path, len(out), "bytes,", len(convs), "convolutions")
