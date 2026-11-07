# Notices

## Code base

- [simoneluconi/Revive360](https://github.com/simoneluconi/Revive360), GNU GPL v3. This repository continues its history.
- Manrope font, SIL Open Font License 1.1: see [docs/manrope-OFL.txt](docs/manrope-OFL.txt).

## Sources of technical information

- [greg-randall/gear360-raw](https://github.com/greg-randall/gear360-raw), GNU GPL v3: lens calibration record and lens model.
- [arellaeast/gear360-revive](https://github.com/arellaeast/gear360-revive), MIT, Copyright (c) 2026 Jacob East: approach to the Open Spherical Camera client in `OscClient.kt`.
- [teccheck/Gear360App](https://github.com/teccheck/Gear360App): no license file. Used only as a reference for the `00VR` stream chunk format. No code from it may be copied here.

Findings from the other projects reviewed are listed in [AGENTS.md](AGENTS.md).

## Open item

`OscClient.kt` and the `00VR` parser came in a single commit (`6f27c5c`). Whether either contains copied code has not been fully established. If `OscClient.kt` keeps code from gear360-revive, the full MIT license text must be added here.
