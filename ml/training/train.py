#!/usr/bin/env python3
"""Deterministic, provenance-recorded MobileNet baseline training."""
from __future__ import annotations
import argparse, hashlib, json, os, platform, random, subprocess
from pathlib import Path
from typing import Any
import numpy as np
import torch
import timm
from PIL import Image, ImageOps
from safetensors.torch import load_file
from torch.utils.data import DataLoader, Dataset
from torchvision.models import mobilenet_v3_small
from torchvision.transforms import InterpolationMode
from torchvision.transforms import functional as TF

def read(path:Path)->Any:return json.loads(path.read_text(encoding="utf-8"))
def sha256(path:Path)->str:
 h=hashlib.sha256()
 with path.open("rb") as f:
  for chunk in iter(lambda:f.read(1048576),b""):h.update(chunk)
 return h.hexdigest()
def git_revision()->str:
 try:return subprocess.check_output(["git","rev-parse","HEAD"],text=True,stderr=subprocess.DEVNULL).strip()
 except (OSError,subprocess.CalledProcessError):return "unavailable"
def seed_all(seed:int)->None:
 os.environ["PYTHONHASHSEED"]=str(seed);random.seed(seed);np.random.seed(seed);torch.manual_seed(seed);torch.use_deterministic_algorithms(True)
 if torch.cuda.is_available():torch.cuda.manual_seed_all(seed)

class ManifestDataset(Dataset):
 def __init__(self,rows,root,class_index,config):self.rows,self.root,self.class_index,self.config=rows,root.resolve(),class_index,config
 def __len__(self):return len(self.rows)
 def __getitem__(self,index):
  row=self.rows[index];path=(self.root/row["local_path"]).resolve()
  if self.root not in path.parents or not path.is_file():raise ValueError(f"asset path missing or outside root: {row['asset_id']}")
  with Image.open(path) as image:
   image=ImageOps.exif_transpose(image).convert("RGB");image=TF.resize(image,self.config["resize_shorter_side"],interpolation=InterpolationMode.BILINEAR,antialias=True);image=TF.center_crop(image,[self.config["input_size"]]*2);tensor=TF.pil_to_tensor(image).float().div(255);tensor=TF.normalize(tensor,self.config["mean"],self.config["std"])
  return tensor,self.class_index[row["class_id"]]

def load_inputs(manifest_path:Path,splits_path:Path,root:Path,catalog_path:Path):
 manifest,splits,catalog=read(manifest_path),read(splits_path),read(catalog_path);manifest_hash=sha256(manifest_path)
 if splits.get("manifest_sha256")!=manifest_hash:raise ValueError("split manifest identity does not match dataset manifest")
 if not(manifest.get("catalog_id")==splits.get("catalog_id")==catalog.get("catalog_id")):raise ValueError("catalog identity mismatch")
 assets={x["asset_id"]:x for x in manifest["assets"] if x.get("review_status")=="approved" and not x.get("exclusion_reason")};by_split={"train":[],"validation":[]}
 for assignment in splits["splits"]:
  name=assignment["split"]
  if name=="test":continue
  if name not in by_split:raise ValueError(f"unsupported training split: {name}")
  asset=assets.get(assignment["asset_id"])
  if asset is None or asset["class_id"]!=assignment["class_id"]:raise ValueError(f"invalid split asset: {assignment['asset_id']}")
  if sha256((root/asset["local_path"]).resolve())!=asset["sha256"]:raise ValueError(f"asset hash mismatch: {asset['asset_id']}")
  by_split[name].append(asset)
 if not by_split["train"] or not by_split["validation"]:raise ValueError("training and validation splits must both be non-empty")
 return by_split,[x["class_id"] for x in catalog["classes"]],manifest_hash,sha256(splits_path)
def state_hash(model):
 h=hashlib.sha256()
 for name,tensor in sorted(model.state_dict().items()):h.update(name.encode());h.update(tensor.detach().cpu().contiguous().numpy().tobytes())
 return h.hexdigest()
def build_model(config,num_classes,initialize=False):
 if config["architecture"]=="mobilenet_v3_small":return mobilenet_v3_small(weights=None,num_classes=num_classes)
 if config["architecture"]!="timm_mobilenetv3_small_100":raise ValueError("unsupported architecture")
 model=timm.create_model("mobilenetv3_small_100",pretrained=False,num_classes=1000)
 if initialize:
  path=Path(config["weights"])
  if not path.is_file() or sha256(path)!=config["weight_sha256"]:raise ValueError("approved pretrained artifact missing or hash mismatch")
  model.load_state_dict(load_file(path),strict=True)
 model.reset_classifier(num_classes)
 return model
def epoch_pass(model,loader,criterion,device,optimizer=None):
 model.train(optimizer is not None);loss_sum=correct=total=0;context=torch.enable_grad() if optimizer else torch.no_grad()
 with context:
  for inputs,labels in loader:
   inputs,labels=inputs.to(device),labels.to(device)
   if optimizer:optimizer.zero_grad(set_to_none=True)
   logits=model(inputs);loss=criterion(logits,labels)
   if optimizer:loss.backward();optimizer.step()
   loss_sum+=loss.item()*labels.size(0);correct+=(logits.argmax(1)==labels).sum().item();total+=labels.size(0)
 return {"loss":loss_sum/total,"accuracy":correct/total,"samples":total}

def main()->int:
 p=argparse.ArgumentParser();p.add_argument("--manifest",type=Path,required=True);p.add_argument("--splits",type=Path,required=True);p.add_argument("--root",type=Path,required=True);p.add_argument("--catalog",type=Path,default=Path("ml/catalog/mvp-car-catalog.json"));p.add_argument("--configs",type=Path,default=Path("ml/training/baselines.json"));p.add_argument("--baseline",default="mobilenet_v3_small_224");p.add_argument("--output",type=Path,required=True);p.add_argument("--seed",type=int,default=1701);p.add_argument("--epochs",type=int);p.add_argument("--resume",type=Path);a=p.parse_args();seed_all(a.seed)
 config=dict(read(a.configs)["baselines"][a.baseline])
 if config["weight_source"]!="random_initialization" and not(config["architecture"]=="timm_mobilenetv3_small_100" and config.get("weight_sha256")):raise ValueError("unapproved weight source")
 epochs=a.epochs if a.epochs is not None else config["epochs"]
 if epochs<1:raise ValueError("epochs must be positive")
 rows,class_ids,manifest_hash,splits_hash=load_inputs(a.manifest,a.splits,a.root,a.catalog);index={x:i for i,x in enumerate(class_ids)};generator=torch.Generator().manual_seed(a.seed)
 loaders={name:DataLoader(ManifestDataset(items,a.root,index,config),batch_size=config["batch_size"],shuffle=name=="train",generator=generator,num_workers=0) for name,items in rows.items()};device=torch.device("cuda" if torch.cuda.is_available() else "cpu");model=build_model(config,len(class_ids),initialize=not a.resume).to(device);optimizer=torch.optim.AdamW(model.parameters(),lr=config["learning_rate"],weight_decay=config["weight_decay"]);start=0;metrics=[];identity={"manifest_sha256":manifest_hash,"splits_sha256":splits_hash,"seed":a.seed,"baseline":a.baseline}
 if a.resume:
  checkpoint=torch.load(a.resume,map_location=device,weights_only=False)
  if checkpoint["identity"]!=identity:raise ValueError("resume checkpoint identity mismatch")
  model.load_state_dict(checkpoint["model"]);optimizer.load_state_dict(checkpoint["optimizer"]);start=checkpoint["epoch"];metrics=checkpoint["metrics"];generator.set_state(checkpoint["generator_state"]);torch.set_rng_state(checkpoint["torch_rng_state"])
  if torch.cuda.is_available() and checkpoint.get("cuda_rng_states"):torch.cuda.set_rng_state_all(checkpoint["cuda_rng_states"])
 criterion=torch.nn.CrossEntropyLoss();a.output.mkdir(parents=True,exist_ok=True)
 for epoch in range(start,epochs):
  metrics.append({"epoch":epoch+1,"train":epoch_pass(model,loaders["train"],criterion,device,optimizer),"validation":epoch_pass(model,loaders["validation"],criterion,device)});torch.save({"identity":identity,"epoch":epoch+1,"model":model.state_dict(),"optimizer":optimizer.state_dict(),"generator_state":generator.get_state(),"torch_rng_state":torch.get_rng_state(),"cuda_rng_states":torch.cuda.get_rng_state_all() if torch.cuda.is_available() else [],"metrics":metrics},a.output/"checkpoint.pt")
 metadata={"schema_version":"1.0.0","identity":identity,"code_revision":git_revision(),"environment_lock_sha256":sha256(Path("ml/requirements.lock")),"catalog_sha256":sha256(a.catalog),"config_sha256":sha256(a.configs),"architecture":config["architecture"],"weight_source":config["weight_source"],"hyperparameters":{**config,"epochs":epochs},"preprocessing":{"orientation":"apply_exif_then_strip_metadata","resize":{"mode":"shorter_side","shorter_side":config["resize_shorter_side"],"interpolation":config["interpolation"]},"crop":{"mode":"center","height":config["input_size"],"width":config["input_size"]},"value_scale":1/255,"normalization":{"mean":config["mean"],"std":config["std"]}},"runtime":{"python":platform.python_version(),"torch":torch.__version__,"device":str(device),"deterministic_algorithms":True},"split_usage":{"training":["train"],"selection":["validation"],"held_out_test":"not_loaded"},"metrics":metrics,"checkpoint_state_sha256":state_hash(model)};(a.output/"run.json").write_text(json.dumps(metadata,indent=2,sort_keys=True)+"\n");print(f"RESULT OK epochs={epochs} device={device} checkpoint_state_sha256={metadata['checkpoint_state_sha256']}");return 0
if __name__=="__main__":raise SystemExit(main())
