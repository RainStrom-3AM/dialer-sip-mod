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
# apply patches/01 and patches/02 (see file headers)
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

- Long-press the Phone icon → **SIP settings** (server, user, auth user,
  password, port, UDP/TCP, receive-calls toggle) or use the persistent
  notification once enabled.
- Dial from the dialpad and pick **SIP** in the account chooser, or use the
  **New SIP call** shortcut / notification action.
- Diagnostics: `adb logcat -s DialerSip PjsipTrace` — registration state,
  call disconnect codes+reasons, full SIP traces, audio-bridge events.

## Known limitations

- The dialer's settings screens are proto/parcel driven and heavily R8
  obfuscated; SIP account editing lives in its own activity (shortcut +
  notification) instead of inside Settings > Calling accounts.
- Outgoing calls show no "via SIP" badge (the dialer has an incoming-only
  template — stock behavior for all third-party accounts).
- Google attestation is neutralized (see patches/02), so attestation-backed
  cloud features degrade.

## License

Original code in `app/src` and the build scripts: MIT. PJSIP and Opus are
under their own licenses. The patched dialer APK contains Google proprietary
code — for personal use only.
