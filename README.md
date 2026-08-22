# Dialer SIP Mod

Adds real SIP calling to the Google Phone app (com.google.android.dialer) on
Android 16 — bundled [PJSIP](https://github.com/pjsip/pjproject) 2.15.1 stack
(pjsua2), Telecom-integrated calls, and the dialer's own in-call UI.

 SIP calls look and behave like cellular calls: same dialer, same call log,
 same in-call screen, SIM/SIP account chooser when dialing.

> Personal modding project, published for educational purposes. The APK is a
> modification of Google's proprietary Dialer — do not redistribute it
> commercially. The Java sources in `app/src` are original code.

## What's inside

| Piece | Purpose |
|---|---|
| `app/src/com/dialersip/` | Java sources: pjsua2 manager, Telecom ConnectionService, foreground registration service, Material 3 settings + dial UI, boot receiver |
| `patches/` | Three patches applied to the decompiled dialer: two crash fixes + the dialpad-overflow settings entry / theme resources |
| `build/build_splice.sh` | Compile → dex → baksmali → splice pipeline |
| `build/module.prop` | Magisk module metadata |
| Release assets | Prebuilt, signed APK (also packaged as a Magisk module zip) |

## Features

- SIP calls in the stock dialer: account chooser (SIM/SIP), stock in-call UI,
  call log with proper caller ID (`From`, `P-Asserted-Identity`,
  `Remote-Party-ID` parsing; numbers match contacts).
- **SIP settings** from the dialpad overflow (⋮) menu - Material 3 screen
  (bundled components, Google-blue palette, automatic dark mode) to add,
  edit, delete the account and start/stop the service.
- Incoming calls: Telecom hand-off via a thread-safe queue; unanswered calls
  that Telecom refuses to present are answered 486 Busy after 8s so the
  caller stops hearing ringback.
- pjsua2 `Call` lifetime is managed explicitly (strong refs + deferred
  `delete()` on the pjlib thread) - letting the GC finalize a call aborts
  the whole app natively.

## Architecture

- **SIP stack**: PJSIP 2.15.1 built from source for arm64-v8a only, API 30,
  `--disable-ssl --disable-video --with-opus` (G.711 + Opus + device AMR via
  MediaCodec, WebRTC AEC, OpenSL ES / JNI audio device).
- **Telecom integration**: a managed (non-self-managed) `ConnectionService`
  registers a `PhoneAccount` with `sip` + `tel` URI schemes, so SIP calls
  render in the stock in-call UI and appear under Settings > Calling accounts.
- **Threading**: all pjsua2 calls are marshalled onto one pjlib-registered
  handler thread. Calling pjlib from Telecom binder threads crashes natively —
  this is load-bearing.
- **Call flow**: place → INVITE via pjsua2; 180 Ringing keeps the connection in
  DIALING (never `setRinging()` on an outgoing connection — it flips the call
  to the incoming UI); answer/reject/hangup posted to the SIP thread; media
  bridged to speaker/mic in `onCallMediaState` (pjsua2 does not auto-connect).
- **Keep-alive**: foreground service (`phoneCall|microphone` type) holds the
  registration, re-registers on network changes, restored after boot.

## Building from source

### 1. Toolchain (Windows, user-space, no WSL needed)

```
JDK 17, Android SDK (platforms;android-36, build-tools;36.0.0, ndk;28.2.13676358, cmake;3.22.1)
swig 4.5 (pip install swig), MSYS2 make 4.4 (from repo.msys2.org, runs under Git Bash)
```

### 2. Native stack

```bash
# Opus (arm64, static)
cmake -G Ninja -DCMAKE_TOOLCHAIN_FILE=$NDK/build/cmake/android.toolchain.cmake \
  -DANDROID_ABI=arm64-v8a -DANDROID_PLATFORM=android-24 -DBUILD_SHARED_LIBS=OFF \
  -DOPUS_BUILD_PROGRAMS=OFF -DOPUS_BUILD_TESTING=OFF -DCMAKE_INSTALL_PREFIX=<prefix> <opus-src>

# pjproject 2.15.1
export ANDROID_NDK_ROOT=<ndk>
./aconfigure --host=aarch64-linux-android \
  --disable-ssl --disable-video --with-opus=<prefix> \
  CC=<ndk-clang> CXX=<ndk-clang++> AR=llvm-ar RANLIB=llvm-ranlib CFLAGS=-fPIC
# config_site.h: #define PJ_CONFIG_ANDROID 1 + include <pj/config_site_sample.h>
make dep && make -j8
# pjsua2 bindings + libpjsua2.so
cd pjsip-apps/src/swig/java && make TARGET_ARCH=arm64-v8a
```

### 3. Decompile, patch, splice, build

```bash
apktool d com.google.android.dialer.apk dialer_decompiled
# apply patches/01, 02 and 03 (see file headers)
# copy app/src sources + edit build/build_splice.sh paths
bash build/build_splice.sh          # -> smali_classes6/ + lib/arm64-v8a/*.so
apktool b dialer_decompiled -o unsigned.apk
zipalign -p 4 unsigned.apk aligned.apk
apksigner sign --ks <your.keystore> --out dialer-sip-signed.apk aligned.apk
```

Manifest additions (already in the shipped APK): `USE_SIP` permission,
`SipConnectionService` (telecom binding), `SipRegistrationService`
(foreground, phoneCall|microphone), `SipBootReceiver`, the two activities,
and launcher-shortcut entries in `res/xml/shortcuts.xml`.

## Installing (Magisk-rooted device)

1. Grab the module zip from the releases (contains the APK + module.prop) or
   build it: `system/product/priv-app/GoogleDialer/GoogleDialer.apk` +
   `module.prop`.
2. `adb push` it, then as root: `magisk --install-module <zip>`
3. If a `/data/app` update of the dialer exists:
   `pm uninstall -k --user 0 com.google.android.dialer && rm -rf /data/app/~~*`
   then reboot, then `pm install-existing --user 0 com.google.android.dialer`.
4. Sign every future build with the same key and updates are just
   `pm install -r <new.apk>` — no reboot needed.

Disable the Magisk module + reboot to return to stock.

## Using it

- Open the dialpad, tap **⋮ → SIP accounts** — or long-press the Phone icon
  (**SIP settings** shortcut), or use the persistent notification's
  **SIP settings** action.
- The settings screen (Material 3, light/dark): server, username, auth
  username, password, port, UDP/TCP, receive-incoming-calls switch, plus
  **Save changes / Delete account / Stop SIP service**.
- Dial any number and pick **SIP** in the account chooser (set the chooser to
  "Ask first" in Settings → Calling accounts → Make calls with), or use the
  **New SIP call** shortcut / notification action.
- Diagnostics: `adb logcat -s DialerSip PjsipTrace` — registration state,
  identity-header extraction (`PAI=... RPID=... From=...`), queue hand-off
  (`stashed/claimed incoming call`), 486 watchdog, call disconnect
  codes+reasons, full SIP traces, audio-bridge events.

## Requirements

| Requirement | Detail |
|---|---|
| Root | **Required** — Magisk module replaces `/product/priv-app/GoogleDialer/` |
| Android | 11 (API 30) through 16; `minSdk=30`, `targetSdk=37` (newer untested) |
| CPU | arm64-v8a only (the bundled PJSIP stack is 64-bit) |
| Base app | Google Phone (com.google.android.dialer) 233.x — patches target this code |

Non-rooted devices cannot install it: the APK is re-signed, so it can never
update over Google's Play-Store-installed dialer. The ConnectionService API
itself does not need root — the system-dialer *replacement* does.

## Non-rooted variant (standalone app)

`DialerSIP-standalone` is the same SIP stack shipped as a **normal
installable app** (`com.rainstrom.dialersip`) — no root, no Magisk, no
replacement of the system dialer. It uses the public Telecom
ConnectionService API, so SIP calls still ring through the stock dialer's
in-call UI, appear in the system call log, and the SIP account shows up in
the Settings -> Calling accounts chooser next to your SIMs.

Differences from the rooted mod:
- No dialpad-overflow menu entry inside the system dialer (that patch
  modifies dialer code) — the app's own icon/notification/shortcuts are
  the entry points.
- The SIP PhoneAccount is not auto-enabled (that needs a privileged
  permission): flip it on once under Settings -> Calling accounts.
- Built from the same sources; the dialer's dex set is bundled as the
  Material/appcompat runtime.

Tested on Android 16 alongside the stock dialer: registration, outgoing,
incoming with caller ID, stock in-call UI.

## FAQ

**Will Play Store updates break it?** The Play Store can't update a
signature-mismatched app, so it won't clobber the mod by itself — but never
re-enable auto-updates for the dialer. Every future mod build signed with the
same key installs over the previous one via `pm install -r` with no reboot.

**Does it work on other dialer versions?** The three smali patches are made
against dialer 233.x internals. Other versions may need the patches re-applied
by hand — the Java sources and build pipeline are version-independent.

**Is it private?** Nothing leaves the device except SIP traffic to the server
you configure. No telemetry added; Google's own attestation is neutralized
(see patches/02) because a re-signed APK can never pass it.

## Known limitations

- While a cellular call is active/connecting, this ROM's Telecom aborts
  incoming-call creation for third-party connection services without ever
  calling our code. The mod detects this (call unclaimed for 8s) and answers
  **486 Busy** so the caller stops hearing ringback; the aborted call is
  logged by the system as a numberless missed entry.
- Outgoing calls show no "via SIP" badge (the dialer has an incoming-only
  template — stock behavior for all third-party accounts). Outgoing call-log
  entries record the dialed SIP URI.
- Hold/reject-with-message are not implemented for SIP calls.
- No TLS transport (UDP + TCP only), arm64 only.
- Call recording exists as a work-in-progress branch (`dev-recording`), not
  shipped.
- Google attestation is neutralized (patches/02), so attestation-backed cloud
  features degrade.

## License

Original code in `app/src` and the build scripts: MIT. PJSIP and Opus are
under their own licenses. The patched dialer APK contains Google proprietary
code — for personal use only.
