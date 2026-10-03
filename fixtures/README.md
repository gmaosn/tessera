# Fixtures

`acbf-xml/` holds the ACBF documents of the official sample books, unchanged, so that every
test can check a byte-for-byte round trip on real files. `Craphound-1.0.acbf` is the ACBF 1.0
edition with its images embedded in base64.

`samples/` (not in git, about 120 MB) holds the complete CBZ books; `tools/fetch-fixtures.sh`
downloads them from the ACBF project (launchpad.net/acbf). Tests that need them are skipped
when they are missing.

The books keep their own licences (Creative Commons, see each document's `<license>`):
Craphound and Anda's Game (Cory Doctorow), NYC2123, Pepper & Carrot (David Revoy),
The Purple Claw, Vision Machine, The Illustrated Book of Bad Arguments (Ali Almossawi).
