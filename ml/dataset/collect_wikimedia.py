#!/usr/bin/env python3
"""Collect individually licensed Wikimedia Commons candidates for human label review."""
from __future__ import annotations
import argparse,hashlib,html,itertools,json,re,time,unicodedata,urllib.parse,urllib.request
from urllib.error import HTTPError
from datetime import datetime,timezone
from html.parser import HTMLParser
from pathlib import Path

API="https://commons.wikimedia.org/w/api.php";USER_AGENT="CategorizerDatasetCollector/1.0 (https://github.com/infopek/categorizer)"
LICENSES={"cc-by-2.0":("CC-BY-2.0","https://creativecommons.org/licenses/by/2.0"),"cc-by-3.0":("CC-BY-3.0","https://creativecommons.org/licenses/by/3.0"),"cc-by-4.0":("CC-BY-4.0","https://creativecommons.org/licenses/by/4.0"),"cc-zero":("CC0-1.0","https://creativecommons.org/publicdomain/zero/1.0"),"cc0":("CC0-1.0","https://creativecommons.org/publicdomain/zero/1.0"),"pd":("Public-Domain","https://commons.wikimedia.org/wiki/Help:Public_domain"),"public-domain":("Public-Domain","https://commons.wikimedia.org/wiki/Help:Public_domain")}
class RateLimited(Exception):pass
class Text(HTMLParser):
 def __init__(self):super().__init__();self.parts=[]
 def handle_data(self,data):self.parts.append(data)
def plain(value):p=Text();p.feed(html.unescape(str(value or "")));return " ".join("".join(p.parts).split())
def https_url(value,fallback):
 value=str(value or fallback)
 if value.startswith("//"):return "https:"+value
 if value.startswith("http://"):return "https://"+value.removeprefix("http://")
 return value
def request(params,retries=6):
 url=API+"?"+urllib.parse.urlencode(params);req=urllib.request.Request(url,headers={"User-Agent":USER_AGENT})
 for attempt in range(retries):
  try:
   with urllib.request.urlopen(req,timeout=30) as response:return json.load(response)
  except HTTPError as error:
   if error.code!=429 or attempt+1==retries:raise
   wait=min(60,5*(2**attempt));print(f"RATE_LIMIT api_wait={wait}s attempt={attempt+1}/{retries}",flush=True);time.sleep(wait)
def download(url,path,max_bytes):
 req=urllib.request.Request(url,headers={"User-Agent":USER_AGENT});h=hashlib.sha256();size=0
 try:
  with urllib.request.urlopen(req,timeout=60) as response,path.open("wb") as output:
   while chunk:=response.read(1048576):
    size+=len(chunk)
    if size>max_bytes:raise ValueError("download exceeds byte limit")
    output.write(chunk);h.update(chunk)
 except HTTPError as error:
  if error.code==429:raise RateLimited("Wikimedia rate limit reached; wait before resuming") from error
  raise
 return h.hexdigest()
def download_with_backoff(url,path,max_bytes,retries=6):
 for attempt in range(retries):
  try:return download(url,path,max_bytes)
  except RateLimited:
   path.unlink(missing_ok=True)
   if attempt+1==retries:raise
   wait=min(60,5*(2**attempt));print(f"RATE_LIMIT wait={wait}s attempt={attempt+1}/{retries}",flush=True);time.sleep(wait)
def license_info(metadata):
 raw=str(metadata.get("License",{}).get("value","")).lower().strip()
 if raw in LICENSES:return LICENSES[raw]
 short=str(metadata.get("LicenseShortName",{}).get("value","")).lower().replace(" ","-")
 return LICENSES.get(short)
def safe_id(pageid,title):return f"commons-{pageid}-"+re.sub(r"[^a-z0-9]+","-",title.lower()).strip("-")[:60]
def candidate_count(assets,class_id):return sum(x["class_id"]==class_id and x["review_status"]!="rejected" for x in assets)
def normalized_tokens(value):
 value=unicodedata.normalize("NFKD",value).encode("ascii","ignore").decode().lower()
 return set(re.sub(r"[^a-z0-9]+"," ",value).split())
def category_names(klass):
 make,model,generation=klass["make"],klass["model"],klass.get("generation_label","")
 if generation:return [f"{make} {generation}",f"{make} {model} ({generation})"]
 names=[f"{make} {model}"]
 if "/" in model:names.extend(f"{make} {part.strip()}" for part in model.split("/"))
 return list(dict.fromkeys(names))
def relevant_category(klass,title):
 tokens=normalized_tokens(title);generation=klass.get("generation_label","")
 if generation:return normalized_tokens(generation)<=tokens
 make=normalized_tokens(klass["make"]);models=[normalized_tokens(x) for x in klass["model"].split("/")]
 return bool(make&tokens) and any(model<=tokens for model in models)
def discovered_category_names(klass,limit=10):
 query=" ".join(filter(None,[klass["make"],klass["model"],klass.get("generation_label","")]))
 data=request({"action":"query","format":"json","formatversion":"2","list":"search","srsearch":query,"srnamespace":"14","srlimit":limit})
 return [x["title"].removeprefix("Category:") for x in data.get("query",{}).get("search",[]) if relevant_category(klass,x["title"])]
def category_file_pages(names,depth=2):
 """Yield file pages from exact Commons categories and their subcategories."""
 categories=[f"Category:{name}" for name in names];seen=set();pageids=[]
 for level in range(depth+1):
  next_categories=[]
  for category in categories:
   continuation=None
   while True:
    params={"action":"query","format":"json","formatversion":"2","list":"categorymembers","cmtitle":category,"cmtype":"file|subcat","cmlimit":"500"}
    if continuation:params["cmcontinue"]=continuation
    data=request(params)
    for member in data.get("query",{}).get("categorymembers",[]):
     if member["ns"]==6 and member["pageid"] not in seen:seen.add(member["pageid"]);pageids.append(member["pageid"])
     elif member["ns"]==14 and level<depth:next_categories.append(member["title"])
    continuation=data.get("continue",{}).get("cmcontinue")
    if not continuation:break
  categories=list(dict.fromkeys(next_categories))
 for start in range(0,len(pageids),50):
  data=request({"action":"query","format":"json","formatversion":"2","pageids":"|".join(map(str,pageids[start:start+50])),"prop":"imageinfo","iiprop":"url|extmetadata|mime|size","iiurlwidth":"1024"})
  yield from data.get("query",{}).get("pages",[])
def search_pages(query,limit):
 data=request({"action":"query","format":"json","formatversion":"2","generator":"search","gsrsearch":query,"gsrnamespace":"6","gsrlimit":limit,"prop":"imageinfo","iiprop":"url|extmetadata|mime|size","iiurlwidth":"1024"})
 return data.get("query",{}).get("pages",[])
QUERY_ALIASES={
 "bmw-3-series-g20":["BMW G20",'incategory:"BMW G20"'],
 "bmw-5-series-g30":["BMW G30",'incategory:"BMW G30"'],
 "volkswagen-t-roc":["VW T-Roc","Volkswagen T-Roc 2018","Volkswagen T-Roc front",'incategory:"Volkswagen T-Roc"'],
 "mercedes-benz-c-class-w205":['incategory:"Mercedes-Benz W205"'],
 "mercedes-benz-c-class-w206":["Mercedes-Benz W206",'incategory:"Mercedes-Benz W206"'],
 "audi-q3":['incategory:"Audi Q3"'],
 "tesla-model-x":["Tesla Model X front","Tesla Model X 2020",'incategory:"Tesla Model X"'],
 "skoda-karoq":["Škoda Karoq",'incategory:"Škoda Karoq"'],
 "lexus-es":["Lexus ES 300h","Lexus ES XV60","Lexus ES XV70",'incategory:"Lexus ES (XV60)"','incategory:"Lexus ES (XZ10)"','incategory:"Lexus ES"'],
}
def main():
 p=argparse.ArgumentParser();p.add_argument("--catalog",type=Path,default=Path("ml/catalog/mvp-car-catalog.json"));p.add_argument("--root",type=Path,required=True);p.add_argument("--manifest",type=Path,required=True);p.add_argument("--class-id",action="append",dest="class_ids");p.add_argument("--per-class",type=int,default=5);p.add_argument("--search-limit",type=int,default=30);p.add_argument("--category-search-limit",type=int,default=10);p.add_argument("--category-depth",type=int,default=2);p.add_argument("--max-bytes",type=int,default=20*1024*1024);p.add_argument("--delay",type=float,default=.5);a=p.parse_args();catalog=json.loads(a.catalog.read_text());a.root.mkdir(parents=True,exist_ok=True)
 value=json.loads(a.manifest.read_text()) if a.manifest.exists() else {"schema_version":"1.0.0","catalog_id":catalog["catalog_id"],"assets":[]}
 for asset in value["assets"]:asset["license_url"]=https_url(asset.get("license_url"),LICENSES.get(asset["license_id"].lower(),("","https://commons.wikimedia.org/wiki/Commons:Reusing_content_outside_Wikimedia"))[1])
 a.manifest.parent.mkdir(parents=True,exist_ok=True);a.manifest.write_text(json.dumps(value,indent=2,sort_keys=True)+"\n");existing={x["asset_id"] for x in value["assets"]};counts={}
 for klass in catalog["classes"]:
  class_id=klass["class_id"];accepted=candidate_count(value["assets"],class_id);generation=klass.get("generation_label","");queries=[f'"{klass["display_name"]}" filetype:bitmap',f'{klass["make"]} {klass["model"]} {generation} filetype:bitmap',f'{klass["make"]} {klass["model"]} automobile filetype:bitmap']
  if a.class_ids and class_id not in a.class_ids:continue
  if accepted>=a.per_class:counts[class_id]=accepted;print(f"CLASS {class_id} candidates={accepted}");continue
  queries=[f'{query} filetype:bitmap' for query in QUERY_ALIASES.get(class_id,[])]+queries
  categories=category_names(klass)+discovered_category_names(klass,a.category_search_limit)
  sources=itertools.chain([category_file_pages(list(dict.fromkeys(categories)),a.category_depth)],(search_pages(query,a.search_limit) for query in queries))
  for pages in sources:
   if accepted>=a.per_class:break
   for page in pages:
    if accepted>=a.per_class:break
    info=(page.get("imageinfo") or [{}])[0];metadata=info.get("extmetadata",{});approved=license_info(metadata)
    if not approved or info.get("mime") not in {"image/jpeg","image/png","image/webp"}:continue
    asset_id=safe_id(page["pageid"],page["title"])
    if asset_id in existing:continue
    extension={"image/jpeg":".jpg","image/png":".png","image/webp":".webp"}[info["mime"]];relative=f"{class_id}/{asset_id}{extension}";path=a.root/relative;path.parent.mkdir(parents=True,exist_ok=True)
    try:digest=download_with_backoff(info.get("thumburl") or info["url"],path,a.max_bytes)
    except RateLimited:
     path.unlink(missing_ok=True);a.manifest.write_text(json.dumps(value,indent=2,sort_keys=True)+"\n");raise SystemExit("RATE_LIMITED: progress saved; wait before resuming")
    except Exception as error:
     path.unlink(missing_ok=True);print(f"SKIP {class_id} {page['title']}: {error}");continue
    author=plain(metadata.get("Artist",{}).get("value")) or "Unknown Commons contributor";license_id,license_url=approved;description=info["descriptionurl"]
    value["assets"].append({"asset_id":asset_id,"class_id":class_id,"local_path":relative,"source":"wikimedia_commons","original_source_url":info["url"],"description_url":description,"author":author,"license_id":license_id,"license_url":https_url(metadata.get("LicenseUrl",{}).get("value"),license_url),"attribution":f"{page['title']} — {author} — {license_id} — {description}","retrieved_at":datetime.now(timezone.utc).isoformat(),"sha256":digest,"label_reviewer":"unassigned","review_status":"pending","duplicate_group":asset_id,"split_group":asset_id,"exclusion_reason":"label_review_required"});existing.add(asset_id);accepted+=1;a.manifest.write_text(json.dumps(value,indent=2,sort_keys=True)+"\n");time.sleep(a.delay)
  counts[class_id]=accepted;a.manifest.parent.mkdir(parents=True,exist_ok=True);a.manifest.write_text(json.dumps(value,indent=2,sort_keys=True)+"\n");print(f"CLASS {class_id} candidates={accepted}")
 print(f"RESULT OK assets={len(value['assets'])} covered_classes={sum(x>0 for x in counts.values())}/{len(counts)} manifest={a.manifest}")
if __name__=="__main__":main()
