# Versioned Model Bundle Contract

Status: draft implementation for `CONTRACT-002`, pending review.

## Bundle boundary

A distributable bundle contains:

- one ONNX file;
- one ordered class-map JSON file;
- one model-manifest JSON file;
- every notice file named by the manifest.

The manifest is the entry point. Consumers validate it and its referenced files before creating an ONNX Runtime session. Filenames are bundle-relative basenames: absolute paths and directory traversal are invalid.

## Deterministic class identity

`models/class-map.schema.json` defines the ordered mapping from output index to the stable class IDs accepted in `ml/catalog/mvp-car-catalog.json`. Array position and each explicit zero-based `index` must match. IDs and display labels must be unique, `class_count` must equal the array length, and the manifest's catalog ID and class count must match the class map.

The SHA-256 is calculated over the class-map file's exact stored bytes. Reformatting or reordering therefore creates a different artifact and requires updating the manifest. Training, Python evaluation, Android inference, and UI lookup must use this same checked-in order; none may sort by label or class ID at runtime.

## Input and preprocessing

`models/model-manifest.schema.json` fixes the input tensor name, element type, layout, four-dimensional shape, and RGB/BGR channel order. It also fixes:

1. EXIF orientation application before metadata removal;
2. aspect-preserving shorter-side resize and interpolation;
3. centered crop dimensions;
4. input value scale;
5. per-channel mean and standard deviation in declared channel order.

Resize is performed in decoded pixel coordinates. Normalization is `(channel * value_scale - mean) / std`. Consumers must not substitute stretch resize, letterboxing, a different interpolation method, or platform-default orientation behavior.

## Output interpretation

The output contract names the tensor, element type, shape, class axis, logits/probability semantics, score transform, ranking tie-break, and requested top-k count. The class-axis length must equal `class_count`. Logits require softmax and probabilities require identity. Ranking is descending score with ascending class index as the deterministic tie-break.

Schema v1 accepts `float32` tensors only. Quantized input or output requires a future schema revision that defines scale, zero-point, axis, and dequantization behavior; a consumer must reject an `int8` or `uint8` v1 manifest rather than guessing.

## Integrity and licenses

The manifest records exact ONNX and class-map SHA-256 values, ONNX byte size, opset, minimum ONNX Runtime version, and required execution providers. A release validator must receive the ONNX file and verify its size and digest before session creation.

Every release candidate and released bundle must name at least one notice file and every source artifact used to derive the model, with its source URL, license conclusion, and license URL. A public download or generic repository license is not a substitute for artifact-specific provenance accepted under `DEC-001`.

## Compatibility policy

- Schema versions use `major.minor.patch`.
- A major change is breaking. Readers reject unsupported majors.
- A minor change may add optional fields or enum values. Strict validators remain pinned to the exact supported schema until deliberately updated; they do not guess at new semantics.
- A patch change clarifies validation without changing serialized meaning.
- Reordering, adding, removing, or renaming a class requires a new catalog ID, class-map hash, model version, and trained model. It is never an in-place display-only edit.
- Changing preprocessing, output semantics, tensor names/shapes, model bytes, or required runtime compatibility requires a new model version and manifest.
- Older album entries retain stable class IDs and their saved display data; application migration must not reinterpret them using a new output index.

Unknown schema versions, missing required fields, unknown enum values, incompatible ONNX Runtime versions, hash/size mismatches, or class-map inconsistencies fail closed before inference.

## Validation

Run:

```bash
python3 verification/contracts/model-bundle/validate_model_bundle_contract.py
```

The contract fixture intentionally has no ONNX binary and is marked `contract_fixture`. Release candidates and released bundles must supply the referenced model file to the validator. No trained weights or model architecture are selected by this contract.
