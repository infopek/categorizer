#!/usr/bin/env python3
"""Fetch the one DEC-001-approved pretrained artifact and verify it fail-closed."""
from __future__ import annotations
import argparse,hashlib,urllib.request
from pathlib import Path

URL="https://huggingface.co/timm/mobilenetv3_small_100.lamb_in1k/resolve/1824797e7887cbec1990e4adbd6675960a36c589/model.safetensors"
SHA256="46d2c063b18125884c48937afa4c49e18128869e52e8db96df48bf0a4d7ff697"

def digest(path:Path)->str:
 h=hashlib.sha256()
 with path.open("rb") as stream:
  for chunk in iter(lambda:stream.read(1048576),b""):h.update(chunk)
 return h.hexdigest()

def main()->int:
 parser=argparse.ArgumentParser();parser.add_argument("--output",type=Path,default=Path("ml/weights/timm-mobilenetv3-small/model.safetensors"));args=parser.parse_args();args.output.parent.mkdir(parents=True,exist_ok=True)
 if args.output.exists() and digest(args.output)==SHA256:print(f"RESULT OK cached sha256={SHA256}");return 0
 temporary=args.output.with_suffix(".download");temporary.unlink(missing_ok=True)
 request=urllib.request.Request(URL,headers={"User-Agent":"categorizer-ml/1.0"})
 try:
  with urllib.request.urlopen(request,timeout=120) as response,temporary.open("wb") as target:
   for chunk in iter(lambda:response.read(1048576),b""):target.write(chunk)
  actual=digest(temporary)
  if actual!=SHA256:raise ValueError(f"pretrained artifact hash mismatch: {actual}")
  temporary.replace(args.output)
 finally:temporary.unlink(missing_ok=True)
 print(f"RESULT OK downloaded sha256={SHA256}");return 0

if __name__=="__main__":raise SystemExit(main())
