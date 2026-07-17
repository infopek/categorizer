# Luxury and Performance Catalog Amendment Proposal

Status: accepted by the repository owner on 2026-07-17 as an amendment to `DEC-001`.

## Purpose

The accepted 150-class catalog provides the everyday-car coverage needed to avoid
false exotic-car predictions, but it underrepresents the product's discovery use
case: identifying an unfamiliar luxury, sports, or exotic car seen in public.

Append the 50 classes in
`ml/catalog/luxury-performance-catalog-proposal.json` to the existing catalog. The
combined catalog becomes `cars-mvp-200-v2`. Existing class IDs and their first 150
positions remain unchanged.

## Scope rules

- Use model-level labels, not engine, body-style, or trim labels.
- Do not split model years or generations in this amendment.
- Preserve the existing Porsche 911 generation labels and other accepted classes.
- Treat visually ambiguous related models as a known evaluation risk; label review
  must use the Commons description page, not appearance alone.
- Use only the asset sources and per-image license rules already accepted by
  `DEC-001`. This amendment approves no new dataset or pretrained-weight source.

## Proposed coverage

The extension adds Ferrari, Lamborghini, McLaren, Aston Martin, Bentley,
Rolls-Royce, Maserati, Lotus, Bugatti, Pagani, and Koenigsegg, plus the Mercedes-AMG
GT, Audi R8, and BMW i8. It includes recognizable older models as well as current
models so the classifier is useful for cars encountered across a broad age range.

## Data-availability gate

Collection is exploratory until each class has individually verified,
commercially usable images. Before a class enters training it must have enough
reviewed examples to support leakage-resistant train, validation, and held-out test
splits. Classes below that threshold must remain unsupported, be collapsed into a
reviewed model-family label, or be deferred; they must not be filled with unclear
licenses or guessed labels merely to reach a numeric target.

## Contract impact

Acceptance requires regenerating the class map and any fixtures that declare the
catalog ID or class count. Existing 150-class checkpoints remain tied to
`cars-mvp-150-v1`; they are not compatible with the proposed 200-output catalog.
No released album entry IDs need migration because stored recognition labels use
stable class IDs.

## Acceptance

The repository owner accepted this proposal on 2026-07-17. The proposed JSON is the
reviewed definition of the classes appended to the accepted catalog. Dependent
class-map fixtures are updated in a separate implementation change.
