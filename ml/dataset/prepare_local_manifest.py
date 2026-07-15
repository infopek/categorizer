#!/usr/bin/env python3
"""Materialize hashes for explicitly configured local metadata; never downloads or copies images."""
import argparse, json
from pathlib import Path
from pipeline import digest

p=argparse.ArgumentParser();p.add_argument("--metadata",type=Path,required=True);p.add_argument("--root",type=Path,required=True);p.add_argument("--output",type=Path,required=True);a=p.parse_args()
value=json.loads(a.metadata.read_text());base=a.root.resolve()
for record in value.get("assets",[]):
 path=(a.root/record["local_path"]).resolve()
 if base not in path.parents or not path.is_file():raise SystemExit(f"invalid local_path: {record['local_path']}")
 record["sha256"]=digest(path)
a.output.write_text(json.dumps(value,indent=2,sort_keys=True)+"\n")
print(f"RESULT OK records={len(value.get('assets',[]))} output={a.output}")
