# ELCO Remote

A minimal Android Wi-Fi remote for ELCO Smart Cloud TVs (and other Android-based TVs).
Buttons: Power, Volume +/−, Mute, D-pad, OK, Back, Home.

## How it controls the TV

| Protocol | Port | Works with |
|---|---|---|
| **ADB over Wi-Fi** (primary) | 5555 | Android / Google TV based sets, incl. most ELCO Smart/Cloud models |
| **Roku ECP** | 8060 | Roku TVs |

Discovery = SSDP multicast (for friendly names) + a fast TCP probe of your /24 subnet on those ports.

## One-time TV setup (ADB)

1. On the TV: **Settings → Device Preferences → About** → click **Build** 7 times to unlock Developer options.
2. **Settings → Device Preferences → Developer options** → turn on **USB debugging** and **Network debugging / ADB over network** (wording varies by model).
3. Phone and TV must be on the same Wi-Fi.
4. Open the app → **Scan** → pick the TV. The TV shows **"Allow debugging?"** — tick *Always allow* and press **OK** with the physical remote.

If scan finds nothing, use **Enter IP** (TV IP is under Settings → Network → About).

## Build

Every push to `main` builds the APKs in GitHub Actions (`.github/workflows/build.yml`).
Local build: `./gradlew assembleDebug` (needs JDK 17 + Android SDK).
