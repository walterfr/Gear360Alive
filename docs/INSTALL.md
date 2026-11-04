# Installing Revive360

The same steps for the Gear 360 2016 (SM-C200) and the Gear 360 2017 (SM-R210). About half an
hour, once. There are two ways, with the same result:

- **[By hand](#by-hand)**: download a few files and copy them to the camera's card. Nothing to
  install on the computer.
- **[With the script](#with-the-script)**: one command per step builds everything from Samsung's
  firmware and prepares the card. For those who want to build it themselves.

## What you need

1. **The camera**, with any firmware: ours is a complete copy of Samsung's last one
   (C200GLU0AQK1 on the 2016, R210GLU0ARB2 on the 2017) with our software added, so installing it
   also updates an older camera.
2. **Its microSD card** and a card reader for the computer. The photos on the card stay.
3. **The app** on an Android phone (Android 10 or later, any brand): from
   [Google Play](https://play.google.com/store/apps/details?id=com.simoneluconi.revive360) or the
   APK in the [latest release](https://github.com/simoneluconi/Revive360/releases/latest).

## By hand

### The files

Download the ones for your camera (they are always the latest version):

| | Gear 360 2016 (SM-C200) | Gear 360 2017 (SM-R210) |
|---|---|---|
| Step 1 | [`smc200.bin`](https://github.com/simoneluconi/Revive360/releases/latest/download/smc200.bin) and [`fwupgrade.txt`](https://github.com/simoneluconi/Revive360/releases/latest/download/fwupgrade.txt) | [`smr210.bin`](https://github.com/simoneluconi/Revive360/releases/latest/download/smr210.bin), [`fwupgrade.txt`](https://github.com/simoneluconi/Revive360/releases/latest/download/fwupgrade.txt) and [`Revive360-2017-software.zip`](https://github.com/simoneluconi/Revive360/releases/latest/download/Revive360-2017-software.zip) |
| Step 2 | [`Revive360-activate.zip`](https://github.com/simoneluconi/Revive360/releases/latest/download/Revive360-activate.zip) | [`Revive360-activate.zip`](https://github.com/simoneluconi/Revive360/releases/latest/download/Revive360-activate.zip) |
| Step 3 | [`Revive360-2016-software.zip`](https://github.com/simoneluconi/Revive360/releases/latest/download/Revive360-2016-software.zip) | not needed |

Everything goes in the card's **main folder**, the one with `DCIM` in it:

- a **`.bin` or `.txt` file**: copy it there as it is, with its name unchanged;
- a **`.zip` file**: open it, select everything inside it and copy that there (not the zip
  itself). If the computer asks whether to replace files, say yes.

### Step 1: update the camera

1. Card in the computer. If it has a file whose name starts with `C200GL` or `R210GL` (Samsung's
   firmware), delete it: the camera would install that one instead.
2. Copy the step 1 files to the card.
3. Card in the camera. Battery charged, **USB cable unplugged**, camera off.
4. **Hold the Shutter button and press power.**
5. The update starts by itself: **don't touch anything** until the camera has restarted (a few
   minutes; lit LEDs are normal).

### Step 2: switch it on

1. Camera off, card in the computer. Delete `smc200.bin` (or `smr210.bin`) and `fwupgrade.txt`:
   the update is done.
2. Copy the contents of `Revive360-activate.zip` to the card.
3. Card in the camera, switch it on, and **don't press any button**. On the 2016 a test screen
   with codes and the battery level shows: that is expected.
4. Wait for **1 beep**, then **2 beeps** (about a minute). Switch the camera off.

The 2017 is ready: switch it on again and [pair the phone](#pair-the-phone).

### Step 3: ready (Gear 360 2016 only)

1. Card in the computer. Delete `cmd.tg` and `info.tg.done`. If there is an `info.tg` instead of
   `info.tg.done`, step 2 did not run: go back to it.
2. Copy the contents of `Revive360-2016-software.zip` to the card.
3. Card back in the camera, switch it on, and [pair the phone](#pair-the-phone).

## With the script

### What else you need

1. **A Windows or Linux computer** with [Docker Desktop](https://www.docker.com/products/docker-desktop/)
   (running) and [Git](https://git-scm.com/downloads). On Windows, type the commands in **Git Bash**.
2. **This project**, downloaded: `git clone https://github.com/simoneluconi/Revive360`, then open a
   terminal in the `Revive360` folder.
3. **One photo taken with this camera** on its card: the script reads the camera's model from it.
4. **Samsung's firmware file** for your camera: click its name below to download it from the
   [XDA thread](https://xdaforums.com/t/mod-port-samsung-gear-360-manager-for-all-devices-updated-10-apr-24-v1-5-00-1-4.3400383/page-93)
   (XDA may ask you to log in), and put it in the `firmware/stock` folder:

   | Camera | File | SHA-256 |
   |---|---|---|
   | Gear 360 2016 | [`C200GLU0AQK1_171121_1257_REV00_user.bin`](https://xdaforums.com/attachments/c200glu0aqk1_171121_1257_rev00_user-bin.5193031/) | `150bc48362555a4812e8871ff581c693f50f80e3e31e4647d2b563e9072c48db` |
   | Gear 360 2017 | [`R210GLU0ARB2_180201_1739_REV00_user.bin`](https://xdaforums.com/attachments/r210glu0arb2_180201_1739_rev00_user-bin.5193015/) | `81765495ee2dfa3f67f0543e8f99f0df2e5310e3f0b10f8dabddbf64015d8324` |

   The SHA-256 is the file's fingerprint: `./install.sh build` checks it and refuses any other
   file, so a wrong or damaged download never reaches the camera.

### The steps

Each command prepares the card and then says what to do on the camera: the same as steps 1-3
by hand.

```sh
./install.sh build      # once; the first time takes a while, it ends with "Built."
./install.sh flash      # card in the computer; then on the camera as in step 1
./install.sh activate   # camera off, card in the computer; then as in step 2
./install.sh sd         # 2016 only, camera off, card in the computer; then as in step 3
```

The script finds the camera model by itself. To choose it, put `c200` or `r210` right after
`./install.sh` (for example `./install.sh r210 flash`).

## Pair the phone

Switch the camera on and wait: it now takes **about 30 seconds, with nothing on the screen**, then
the LEDs go around it and it beeps once. Within 3 minutes, open the app and tap **Find the
camera**. It finds it by itself; Android asks once to confirm the camera's network
(`G360R-...`). Nothing to type.

Done. The app's home lists your cameras: tap one to connect to it. The camera's Wi-Fi switches on
only while the app needs it.

## Updating later

- **By hand:** copy the contents of the new `Revive360-2016-software.zip` (or
  `Revive360-2017-software.zip`) to the card, replacing the files. The settings and the pairing
  stay.
- **With the script:** `./install.sh build`, then `./install.sh sd` with the card.

When a release also changes the firmware, its notes say so: then install again from step 1.

## Going back to Samsung's firmware

- **By hand:** download Samsung's file from the table in [With the script](#what-else-you-need)
  and copy it to the card renamed **`smc200.bin`** (2016) or **`smr210.bin`** (2017), with
  `fwupgrade.txt` next to it. If Windows hides file extensions, the new name shows as just
  `smc200`: type only that, or the file becomes `smc200.bin.bin`.
- **With the script:** `./install.sh stock`.

Then the same buttons as in step 1. The camera is back as it came from Samsung, and the `g360r`
folder on the card is no longer used: you can delete it.

## If something goes wrong

- **Nothing on the screen after switching on:** normal, wait a full minute.
- **3 beeps:** an error. Camera off, card in the computer: the camera's logs are in the card's
  `g360r/log` folder (`./install.sh status` shows them too).
- **The update doesn't start (the camera starts as usual):** unplug the USB cable and charge the
  battery.
- **Stuck:** hold power until the camera is off, **take the card out**, switch it on: without the
  card it starts as Samsung's.

Changing the camera's software is at your own risk; Samsung's firmware can always be put back as
above. The details behind every step are in the [developer notes](DEVELOPMENT.md) and
[camera/fw/README.md](../camera/fw/README.md).
