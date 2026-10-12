# ThorVault Generation 3–7 engine

Supported save classes:

- SAV3RS
- SAV3E
- SAV3FRLG
- SAV4DP
- SAV4Pt
- SAV4HGSS
- SAV5BW
- SAV5B2W2
- SAV6XY
- SAV6AO
- SAV7SM
- SAV7USUM

Supported entity formats:

- PK3
- PK4
- PK5
- PK6
- PK7

The generic import, compatibility, inspection, and write commands are shared by
all supported generations. Generated saves are reopened and checked for the
correct payload, valid checksums, and an unchanged generation.

Generation 3, 4, and 5 source-slot clearing is blocked until DS copy writes are
validated in an emulator.
