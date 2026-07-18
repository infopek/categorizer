# Photo privacy and archive red-team verification

Status: passed on 2026-07-18 under VER-004.

Six Android device tests passed on the accepted Samsung Galaxy S20 FE. The suite
independently inspected an exported managed JPEG with Android `ExifInterface` and
confirmed that source GPS latitude, camera make, and camera model were absent. The
managed image pipeline had first normalized orientation and re-encoded the source.

The adversarial archive corpus rejected checksum corruption, missing members,
duplicate IDs, incompatible schema versions, relative traversal, absolute paths,
compressed image members, declared images above 50 MiB, and duplicate ZIP member
names. Album state remained unchanged after validation failures.

Failure injection after the first installed image rolled back both the installed file
and album metadata and removed the recovery journal. A local mutation after preview
invalidated the import plan before commit. The normal export/import round trip
preserved the managed image bytes and album record.

Application-private album images are stored below `files/images`. The only configured
`FileProvider` path is `cache/camera-capture`, used for pending camera output; managed
album images are not included in a provider root. The provider is non-exported and
grants only explicit URI permissions.

Residual risk is bounded by the accepted archive limits: 10,000 entries, 50 MiB per
image, 10 GiB total declared and observed image bytes, and a 10 MiB manifest. Images
must be uncompressed `ZIP_STORED` members, preventing a compressed image zip bomb.
