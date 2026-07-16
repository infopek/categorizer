#!/usr/bin/env python3
"""Evaluate a frozen held-out split without exposing it to training or selection."""
from __future__ import annotations
import argparse, json, math, platform
from collections import Counter
from pathlib import Path
from typing import Any
import numpy as np
import torch
from torch.utils.data import DataLoader
from ml.training.train import ManifestDataset,build_model,git_revision,read,sha256,state_hash

def ranked(logits:list[float])->list[int]:
 values=np.asarray(logits,dtype=np.float64)
 if values.ndim!=1 or not np.isfinite(values).all():raise ValueError("model output contains NaN/Inf or has invalid shape")
 return np.lexsort((np.arange(len(values)), -values)).tolist()

def calculate(records:list[dict[str,Any]],class_ids:list[str],min_support:int=1)->dict[str,Any]:
 if not records:raise ValueError("held-out records are empty")
 support=Counter(x["target_index"] for x in records);missing=[class_ids[i] for i in range(len(class_ids)) if support[i]<min_support]
 if missing:raise ValueError("insufficient held-out support: "+",".join(missing))
 confusion=[[0]*len(class_ids) for _ in class_ids];failures=[];top1=top5=0;confidence=[]
 per={i:{"class_id":class_ids[i],"support":support[i],"top_one_correct":0,"top_five_correct":0} for i in range(len(class_ids))}
 for row in records:
  target=row["target_index"];order=ranked(row["logits"]);pred=order[0];k=min(5,len(class_ids));one=int(pred==target);five=int(target in order[:k]);top1+=one;top5+=five;per[target]["top_one_correct"]+=one;per[target]["top_five_correct"]+=five;confusion[target][pred]+=1
  shifted=np.asarray(row["logits"],dtype=np.float64)-max(row["logits"]);prob=np.exp(shifted);prob/=prob.sum();confidence.append((float(prob[pred]),one))
  if not one:failures.append({"asset_id":row["asset_id"],"target_class_id":class_ids[target],"predicted_class_ids":[class_ids[i] for i in order[:k]]})
 bins=[]
 for lower in np.linspace(0,0.9,10):
  values=[x for x in confidence if lower<=x[0]<(lower+0.1) or lower==0.9 and x[0]<=1]
  if values:bins.append({"lower":round(float(lower),1),"upper":round(float(lower+0.1),1),"samples":len(values),"mean_confidence":sum(x[0] for x in values)/len(values),"accuracy":sum(x[1] for x in values)/len(values)})
 rows=[]
 for i in range(len(class_ids)):
  x=per[i];x["top_one_accuracy"]=x.pop("top_one_correct")/x["support"];x["top_five_accuracy"]=x.pop("top_five_correct")/x["support"];rows.append(x)
 total=len(records);macro1=sum(x["top_one_accuracy"] for x in rows)/len(rows);macro5=sum(x["top_five_accuracy"] for x in rows)/len(rows)
 return {"aggregate":{"samples":total,"top_one_accuracy":top1/total,"top_five_accuracy":top5/total,"macro_top_one_accuracy":macro1,"macro_top_five_accuracy":macro5,"weighted_top_one_accuracy":sum(x["top_one_accuracy"]*x["support"] for x in rows)/total,"weighted_top_five_accuracy":sum(x["top_five_accuracy"]*x["support"] for x in rows)/total},"per_class":rows,"confusion_matrix":{"class_ids":class_ids,"counts":confusion},"calibration":{"method":"10_equal_width_confidence_bins","bins":bins},"failure_gallery":failures}

def held_out(manifest:dict,splits:dict,root:Path)->list[dict]:
 assets={x["asset_id"]:x for x in manifest["assets"] if x.get("review_status")=="approved" and not x.get("exclusion_reason")};rows=[];base=root.resolve()
 for x in splits["splits"]:
  if x["split"]!="test":continue
  asset=assets.get(x["asset_id"])
  if not asset or asset["class_id"]!=x["class_id"]:raise ValueError(f"invalid test asset: {x['asset_id']}")
  path=(root/asset["local_path"]).resolve()
  if base not in path.parents or not path.is_file():raise ValueError(f"asset path missing or outside root: {asset['asset_id']}")
  if sha256(path)!=asset["sha256"]:raise ValueError(f"asset hash mismatch: {asset['asset_id']}")
  rows.append(asset)
 return rows

def main()->int:
 p=argparse.ArgumentParser();p.add_argument("--manifest",type=Path,required=True);p.add_argument("--splits",type=Path,required=True);p.add_argument("--root",type=Path,required=True);p.add_argument("--catalog",type=Path,default=Path("ml/catalog/mvp-car-catalog.json"));p.add_argument("--configs",type=Path,default=Path("ml/training/baselines.json"));p.add_argument("--checkpoint",type=Path,required=True);p.add_argument("--run-metadata",type=Path,required=True);p.add_argument("--output",type=Path,required=True);p.add_argument("--min-support",type=int,default=1);p.add_argument("--unsupported-logits",type=Path);a=p.parse_args()
 manifest,splits,catalog,configs,run=read(a.manifest),read(a.splits),read(a.catalog),read(a.configs),read(a.run_metadata);identity={"manifest_sha256":sha256(a.manifest),"splits_sha256":sha256(a.splits),"seed":run["identity"]["seed"],"baseline":run["identity"]["baseline"]}
 if splits.get("manifest_sha256")!=identity["manifest_sha256"] or run.get("identity")!=identity:raise ValueError("dataset/split/run identity mismatch")
 if run.get("catalog_sha256")!=sha256(a.catalog) or run.get("config_sha256")!=sha256(a.configs) or run.get("environment_lock_sha256")!=sha256(Path("ml/requirements.lock")):raise ValueError("catalog/config/environment identity mismatch")
 classes=catalog["classes"];class_ids=[x["class_id"] for x in classes]
 if catalog.get("class_count")!=len(classes) or manifest.get("catalog_id")!=catalog.get("catalog_id") or splits.get("catalog_id")!=catalog.get("catalog_id"):raise ValueError("class map mismatch")
 checkpoint=torch.load(a.checkpoint,map_location="cpu",weights_only=False)
 if checkpoint.get("identity")!=identity:raise ValueError("checkpoint identity mismatch")
 config=configs["baselines"][identity["baseline"]];model=build_model(config,len(class_ids));model.load_state_dict(checkpoint["model"]);model.eval()
 if state_hash(model)!=run.get("checkpoint_state_sha256"):raise ValueError("checkpoint state identity mismatch")
 rows=held_out(manifest,splits,a.root);dataset=ManifestDataset(rows,a.root,{x:i for i,x in enumerate(class_ids)},config);records=[]
 with torch.no_grad():
  for offset,(images,labels) in enumerate(DataLoader(dataset,batch_size=config["batch_size"],shuffle=False,num_workers=0)):
   logits=model(images)
   if logits.ndim!=2 or logits.shape[1]!=len(class_ids) or not torch.isfinite(logits).all():raise ValueError("model output contains NaN/Inf or has invalid shape")
   start=offset*config["batch_size"]
   for i in range(len(labels)):records.append({"asset_id":rows[start+i]["asset_id"],"target_index":int(labels[i]),"logits":logits[i].tolist()})
 metrics=calculate(records,class_ids,a.min_support);unsupported=[]
 if a.unsupported_logits:
  for item in read(a.unsupported_logits):
   order=ranked(item["logits"]);shift=np.asarray(item["logits"])-max(item["logits"]);prob=np.exp(shift);prob/=prob.sum();unsupported.append({"asset_id":item["asset_id"],"max_confidence":float(prob[order[0]]),"predicted_class_id":class_ids[order[0]],"entropy":float(-sum(x*math.log(max(x,1e-12)) for x in prob))})
 report={"schema_version":"1.0.0","protocol":"frozen-held-out-v1","thresholds":{"minimum_top_five_accuracy":0.8,"minimum_per_class_support":a.min_support},"identities":{**identity,"catalog_sha256":sha256(a.catalog),"environment_lock_sha256":sha256(Path("ml/requirements.lock")),"checkpoint_state_sha256":state_hash(model),"training_code_revision":run["code_revision"],"evaluation_code_revision":git_revision()},"runtime":{"python":platform.python_version(),"torch":torch.__version__,"device":"cpu"},"supported_class_metrics":metrics,"gate":{"top_five_passed":metrics["aggregate"]["top_five_accuracy"]>=0.8},"unsupported_evidence":{"included_in_supported_gate":False,"samples":unsupported}};a.output.parent.mkdir(parents=True,exist_ok=True);a.output.write_text(json.dumps(report,indent=2,sort_keys=True)+"\n");print(f"RESULT OK samples={len(records)} top1={metrics['aggregate']['top_one_accuracy']:.6f} top5={metrics['aggregate']['top_five_accuracy']:.6f} gate={report['gate']['top_five_passed']}");return 0
if __name__=="__main__":raise SystemExit(main())
