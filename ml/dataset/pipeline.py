#!/usr/bin/env python3
"""Validate licensed local assets and create deterministic leakage-resistant splits."""
from __future__ import annotations
import argparse, hashlib, json, re
from collections import Counter, defaultdict
from pathlib import Path
from typing import Any
from PIL import Image, UnidentifiedImageError

LICENSES={"Public-Domain","CC0-1.0","CC-BY-2.0","CC-BY-3.0","CC-BY-4.0","LicenseRef-LocalFixture"}
SOURCES={"wikimedia_commons","open_images_v6","local_fixture"}
REQUIRED={"asset_id","class_id","local_path","source","original_source_url","description_url","author","license_id","license_url","attribution","retrieved_at","sha256","label_reviewer","review_status","duplicate_group","split_group"}
OPTIONAL={"exclusion_reason"}; IDENT=re.compile(r"^[A-Za-z0-9._-]+$"); SHA=re.compile(r"^[a-f0-9]{64}$")

def read(path:Path)->Any:return json.loads(path.read_text(encoding="utf-8"))
def digest(path:Path)->str:
 h=hashlib.sha256()
 with path.open("rb") as f:
  for chunk in iter(lambda:f.read(1048576),b""):h.update(chunk)
 return h.hexdigest()
def dhash(path:Path)->int:
 with Image.open(path) as image:p=list(image.convert("L").resize((9,8)).get_flattened_data())
 return sum((p[r*9+c]>p[r*9+c+1])<<(r*8+c) for r in range(8) for c in range(8))

def validate(manifest:dict[str,Any],root:Path,catalog_path:Path):
 catalog=read(catalog_path); classes=catalog.get("classes",[]); accepted={x["class_id"] for x in classes}
 if catalog.get("status")!="accepted" or catalog.get("class_count")!=len(classes):raise ValueError("catalog is not accepted/consistent")
 if set(manifest)!={"schema_version","catalog_id","assets"} or manifest.get("schema_version")!="1.0.0" or manifest.get("catalog_id")!=catalog["catalog_id"]:raise ValueError("invalid manifest envelope")
 errors=[]; usable=[]; seen=set(); exclusions=Counter(); base=root.resolve()
 for i,x in enumerate(manifest.get("assets",[])):
  tag=f"asset[{i}]"
  if not isinstance(x,dict) or not REQUIRED<=set(x) or not set(x)<=REQUIRED|OPTIONAL:errors.append(f"{tag}: invalid fields");continue
  if not IDENT.fullmatch(str(x["asset_id"])) or x["asset_id"] in seen:errors.append(f"{tag}: invalid/duplicate asset_id")
  seen.add(x["asset_id"])
  if x["class_id"] not in accepted:errors.append(f"{tag}: class outside catalog")
  if x["source"] not in SOURCES or x["license_id"] not in LICENSES:errors.append(f"{tag}: unapproved source/license")
  if not all(str(x[k]).strip() for k in REQUIRED):errors.append(f"{tag}: blank required value")
  if not all(str(x[k]).startswith("https://") for k in ("original_source_url","description_url","license_url")):errors.append(f"{tag}: public HTTPS URLs required")
  if not SHA.fullmatch(str(x["sha256"])):errors.append(f"{tag}: invalid sha256")
  if x["review_status"]!="approved" or x.get("exclusion_reason"):exclusions[x.get("exclusion_reason",x["review_status"])]+=1;continue
  path=(root/x["local_path"]).resolve()
  if base not in path.parents or not path.is_file():errors.append(f"{tag}: path missing/escapes root");continue
  if digest(path)!=x["sha256"]:errors.append(f"{tag}: hash mismatch");continue
  try:usable.append((x,dhash(path)))
  except (UnidentifiedImageError,OSError):errors.append(f"{tag}: corrupt image")
 exact=defaultdict(list)
 for x,h in usable:exact[x["sha256"]].append(x)
 for values in exact.values():
  if len(values)>1 and len({x["duplicate_group"] for x in values})>1:errors.append("exact duplicate spans duplicate groups: "+",".join(x["asset_id"] for x in values))
  if len({x["class_id"] for x in values})>1:errors.append("exact duplicate has conflicting class labels: "+",".join(x["asset_id"] for x in values))
 pairs=[]
 for i,(left,lh) in enumerate(usable):
  for right,rh in usable[i+1:]:
   distance=(lh^rh).bit_count()
   if distance<=4:
    pairs.append({"left":left["asset_id"],"right":right["asset_id"],"distance":distance})
    if left["class_id"]!=right["class_id"]:errors.append(f"near duplicate has conflicting class labels: {left['asset_id']}/{right['asset_id']}")
    if left["split_group"]!=right["split_group"]:errors.append(f"near duplicate spans split groups: {left['asset_id']}/{right['asset_id']}")
 if errors:raise ValueError("\n".join(sorted(set(errors))))
 return usable,{"usable_assets":len(usable),"excluded_assets":sum(exclusions.values()),"exclusion_reasons":dict(sorted(exclusions.items())),"per_class":dict(sorted(Counter(x["class_id"] for x,_ in usable).items())),"near_duplicate_pairs":pairs}

def split(usable,seed):
 grouped=defaultdict(lambda:defaultdict(list))
 for x,_ in usable:grouped[x["class_id"]][x["split_group"]].append(x)
 assignment={}
 for class_id,groups in sorted(grouped.items()):
  ordered=sorted(groups,key=lambda g:hashlib.sha256(f"{seed}:{class_id}:{g}".encode()).hexdigest());counts=Counter();total=sum(map(lambda g:len(groups[g]),ordered));targets={"train":total*.7,"validation":total*.15,"test":total*.15}
  for group in ordered:
   choice=min(targets,key=lambda name:(counts[name]/targets[name],name));assignment[(class_id,group)]=choice;counts[choice]+=len(groups[group])
 return assignment
def main()->int:
 p=argparse.ArgumentParser();p.add_argument("--manifest",type=Path,required=True);p.add_argument("--root",type=Path,required=True);p.add_argument("--catalog",type=Path,default=Path("ml/catalog/mvp-car-catalog.json"));p.add_argument("--seed",default="categorizer-v1");p.add_argument("--output",type=Path,required=True);p.add_argument("--report",type=Path,required=True);a=p.parse_args()
 manifest=read(a.manifest);usable,report=validate(manifest,a.root,a.catalog);assignment=split(usable,a.seed);rows=[{"asset_id":x["asset_id"],"class_id":x["class_id"],"split":assignment[(x["class_id"],x["split_group"])]} for x,_ in sorted(usable,key=lambda y:y[0]["asset_id"])]
 result={"schema_version":"1.0.0","catalog_id":manifest["catalog_id"],"seed":a.seed,"manifest_sha256":hashlib.sha256(a.manifest.read_bytes()).hexdigest(),"splits":rows};encoded=json.dumps(result,indent=2,sort_keys=True)+"\n";a.output.write_text(encoded);report["split_counts"]=dict(sorted(Counter(x["split"] for x in rows).items()));report["split_sha256"]=hashlib.sha256(encoded.encode()).hexdigest();a.report.write_text(json.dumps(report,indent=2,sort_keys=True)+"\n");print(f"RESULT OK assets={len(usable)} manifest_sha256={result['manifest_sha256']} split_sha256={report['split_sha256']}");return 0
if __name__=="__main__":raise SystemExit(main())
