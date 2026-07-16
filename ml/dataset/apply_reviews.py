#!/usr/bin/env python3
"""Apply explicit human label decisions to a pending local dataset manifest."""
import argparse,json
from pathlib import Path
p=argparse.ArgumentParser();p.add_argument("--manifest",type=Path,required=True);p.add_argument("--decisions",type=Path,required=True);p.add_argument("--reviewer",required=True);p.add_argument("--output",type=Path,required=True);a=p.parse_args();value=json.loads(a.manifest.read_text());decisions=json.loads(a.decisions.read_text());allowed={"approved","rejected"}
unknown=set(decisions)-{x["asset_id"] for x in value["assets"]}
if unknown:raise SystemExit("unknown asset IDs: "+",".join(sorted(unknown)))
for asset in value["assets"]:
 decision=decisions.get(asset["asset_id"])
 if decision is None:continue
 if decision not in allowed:raise SystemExit(f"invalid decision for {asset['asset_id']}")
 asset["review_status"]=decision;asset["label_reviewer"]=a.reviewer
 if decision=="approved":asset.pop("exclusion_reason",None)
 else:asset["exclusion_reason"]="human_label_rejected"
a.output.write_text(json.dumps(value,indent=2,sort_keys=True)+"\n");print(f"RESULT OK reviewed={len(decisions)} approved={sum(x=='approved' for x in decisions.values())} rejected={sum(x=='rejected' for x in decisions.values())}")
