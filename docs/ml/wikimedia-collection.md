# Wikimedia Commons candidate collection

The collector queries Commons file search and `imageinfo` extended metadata, then downloads only files individually marked Public Domain, CC0, or CC BY 2.0/3.0/4.0. ShareAlike, unclear, non-bitmap, oversized, and failed downloads are excluded. This automates provenance capture, not legal or label approval; Commons itself tells reusers to verify each file and its attribution requirements.

```bash
.venv/bin/python ml/dataset/collect_wikimedia.py --root ml/datasets/wikimedia/images --manifest ml/datasets/wikimedia/pending-manifest.json --per-class 5
```

Raw images and generated manifests remain ignored. Every candidate starts as `pending` with `label_review_required`. Review the image against its proposed class and description page, then create a JSON decision map such as `{"commons-123-example":"approved"}` and apply it:

```bash
.venv/bin/python ml/dataset/render_review.py --manifest ml/datasets/wikimedia/pending-manifest.json --root ml/datasets/wikimedia/images --output ml/datasets/wikimedia/review.html
.venv/bin/python ml/dataset/apply_reviews.py --manifest ml/datasets/wikimedia/pending-manifest.json --decisions /local/decisions.json --reviewer reviewer-name --output ml/datasets/wikimedia/reviewed-manifest.json
```

Only reviewed `approved` records can enter the deterministic split pipeline. Generation-specific classes require visible evidence for that generation; ambiguous views must be rejected. A useful training set needs substantially more than five images per class—the small collection pass measures search coverage before committing bandwidth and review effort.
