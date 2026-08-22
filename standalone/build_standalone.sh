#!/bin/bash
# Builds the NON-ROOTED standalone DialerSIP app (com.rainstrom.dialersip)
# from a decompiled+spliced dialer tree (run build/build_splice.sh first).
#
# What it does:
#   1. assembles the standalone tree: res/ + lib/ + ALL smali dex sources
#      (the dialer's dex set is the Material/appcompat runtime) but OUR
#      minimal manifest under a new package name
#   2. apktool b -> zipalign (16K pages) -> apksigner
#
# Set the variables below (or export them) before running.
set -e

TOOLS=/e/sip-build-tools
DECOMPILED="E:/New folder/dialer_decompiled"   # spliced rooted tree
JDK=$TOOLS/jdk17
BT=$TOOLS/android-sdk/build-tools/36.0.0
WORK=$TOOLS/standalone-build
OUT=$TOOLS/out
KS=${KS:-$TOOLS/sip-dialer.keystore}           # use YOUR keystore for own builds
KS_ALIAS=${KS_ALIAS:-sipdialer}
KS_PASS=${KS_PASS:-redacted}

HERE="$(cd "$(dirname "$0")" && pwd)"

rm -rf "$WORK"
mkdir -p "$WORK"
cp -r "$DECOMPILED/res"          "$WORK/res"
cp -r "$DECOMPILED/lib"          "$WORK/lib"
cp -r "$DECOMPILED/smali_classes6" "$WORK/smali_classes6"      # our code + pjsua2
for d in smali smali_classes2 smali_classes3 smali_classes4 smali_classes5; do
    cp -r "$DECOMPILED/$d" "$WORK/$d"                          # material/appcompat runtime
done
cp "$HERE/AndroidManifest.xml" "$WORK/AndroidManifest.xml"
cp "$HERE/apktool.yml"         "$WORK/apktool.yml"

export JAVA_HOME="$(cygpath -m "$JDK")"
"$JDK/bin/java.exe" -jar "$TOOLS/apktool.jar" b "$(cygpath -m "$WORK")" \
    -o "$OUT/standalone-unsigned.apk"
"$BT/zipalign.exe" -P 16 -f 4 "$OUT/standalone-unsigned.apk" "$OUT/standalone-aligned.apk"
export MSYS_NO_PATHCONV=1
cmd /c "$(cygpath -w "$BT/apksigner.bat") sign --ks $(cygpath -m "$KS") --ks-key-alias $KS_ALIAS --ks-pass pass:$KS_PASS --key-pass pass:$KS_PASS --out $(cygpath -m "$OUT/DialerSIP-standalone.apk") $(cygpath -m "$OUT/standalone-aligned.apk")"
echo "=== STANDALONE DONE ==="
ls -la "$OUT/DialerSIP-standalone.apk"
