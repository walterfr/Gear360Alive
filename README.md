# Gear360 Alive

Simple Android app for the Samsung Gear 360 (2017, SM-R210), in the style of modern 360 camera apps. It runs on the camera's **original firmware** and does not change the camera.

**Status: early development. It cannot connect to a camera yet.** There is no release to install.

## Plan

[docs/PLANO.md](docs/PLANO.md) (in Portuguese) has the plan, the camera safety rules and the test results.

On a real SM-R210 with original firmware (`R210GLU0ARB2`), without Bluetooth, the camera's "Connect to iOS" menu exposes its UPnP server, and query actions answer. Capture and live view are not tested yet.

## Camera safety

This project does not install anything on the camera. The app has no firmware path, and a test (`StockSafetyTest`) keeps it that way.

`camera/`, `install.sh`, `firmware/` and `docs/INSTALL.md` come from Revive360 and are not used by this app. **They change the camera's firmware: do not use them on a camera you cannot afford to lose.**

## Build

Needs Android Studio or JDK 17 with the Android SDK.

```sh
./gradlew testDebugUnitTest assembleDebug
```

## Credits and license

Gear360 Alive is built on [Revive360](https://github.com/simoneluconi/Revive360) by Simone Luconi, and keeps its history and its [GNU GPL v3](LICENSE) license. See [NOTICE.md](NOTICE.md).

This is an independent project, not made, affiliated with or endorsed by Samsung or by the Revive360 project. Samsung and Gear 360 are trademarks of Samsung Electronics, used here only to say which camera the app works with.
