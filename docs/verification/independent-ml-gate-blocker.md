# Independent ML accuracy gate blocker

Status: externally blocked under VER-003.

The Figshare article inventory contains 189 files and approximately 315.34 GiB of
image archives, but it does not publish `test_idx.npy` or `train_val_idx.npy`. The
publisher's supplied `model_test.py` loads `preparation/test_idx.npy`, while
`run_acc.py` loads `preparation/train_val_idx.npy`; neither script creates the held-out
test split or records its membership.

Downloading all archives therefore cannot reproduce the publisher's held-out result.
Creating a new random split would overlap unknown training examples and would not be
independent evidence. VER-003 must remain blocked until the publisher supplies the
exact indices, a disjoint externally labeled evaluation set is accepted, or the model
is retrained from a fully recorded split.

Device latency, resource, bundle-integrity, and fixture-equivalence evidence remain
valid, but they must not be represented as satisfying the missing 80% held-out
top-five accuracy gate.

Source article: Figshare Plus article 29135618, DOI
`10.25452/figshare.plus.29135618`, inventory checked 2026-07-18.
