#!/bin/bash
# Compile com.dialersip + pjsua2 bindings -> dex -> smali -> splice into decompiled APK.
set -e

TOOLS=/e/sip-build-tools
JDK=$TOOLS/jdk17
ANDROID_JAR=$TOOLS/android-sdk/platforms/android-36/android.jar
D8=$TOOLS/android-sdk/build-tools/36.0.0/d8.bat
BAKLIB=$TOOLS/baksmali-lib
WORK=$TOOLS/splice-work
APK="E:/New folder/dialer_decompiled"

rm -rf "$WORK"
mkdir -p "$WORK/classes" "$WORK/dex"

# 1. gather sources: our app code + generated pjsua2 bindings
find "$TOOLS/app-src" -name "*.java" > "$WORK/sources.txt"
BINDINGS=$TOOLS/pjproject/pjsip-apps/src/swig/java/android/pjsua2/src/main/java
find "$BINDINGS" -name "*.java" >> "$WORK/sources.txt"
sed -i 's|^/e/|E:/|' "$WORK/sources.txt"
echo "sources: $(wc -l < "$WORK/sources.txt")"

# 2. compile (Java 8 bytecode; classpath = framework + bundled material/androidx
#    AAR classes for compile-time resolution - runtime resolves inside the APK dex.
#    Multi-path classpath goes through an @argfile with E:/ paths to dodge MSYS mangling.)
CP="$(cygpath -m "$ANDROID_JAR");$(cygpath -m "$TOOLS/material-classes/classes.jar");$(cygpath -m "$TOOLS/material-classes")"
printf -- '-cp "%s"\n' "$CP" > "$WORK/javac.args"
"$JDK/bin/javac.exe" -source 8 -target 8 \
  -Xlint:-options \
  -nowarn -encoding UTF-8 \
  @"$WORK/javac.args" \
  -d "$WORK/classes" \
  @"$WORK/sources.txt"

# 3. dex (d8 takes jars, not class directories)
"$JDK/bin/jar.exe" cf "$WORK/classes.jar" -C "$WORK/classes" .
export JAVA_HOME="E:\\sip-build-tools\\jdk17"
cmd //c "$(cygpath -w "$D8") --release --min-api 30 --lib $(cygpath -w "$ANDROID_JAR") --output $(cygpath -w "$WORK/dex") $(cygpath -w "$WORK/classes.jar")"
ls -la "$WORK/dex"

# 4. baksmali -> smali_classes6
rm -rf "$APK/smali_classes6"
BCP="$(cygpath -w "$BAKLIB/baksmali.jar");$(cygpath -w "$BAKLIB/dexlib2.jar");$(cygpath -w "$BAKLIB/util.jar");$(cygpath -w "$BAKLIB/guava.jar");$(cygpath -w "$BAKLIB/jsr305.jar");$(cygpath -w "$BAKLIB/failureaccess.jar");$(cygpath -w "$BAKLIB/jcommander.jar");$(cygpath -w "$BAKLIB/antlr4rt.jar")"
"$JDK/bin/java.exe" -cp "$BCP" \
  org.jf.baksmali.Main disassemble "$(cygpath -w "$WORK/dex/classes.dex")" -o "$(cygpath -w "$APK/smali_classes6")"

# 5. native libs
cp "$TOOLS/pjproject/pjsip-apps/src/swig/java/android/pjsua2/src/main/jniLibs/arm64-v8a/"*.so "$APK/lib/arm64-v8a/"

echo "=== SPLICE DONE ==="
find "$APK/smali_classes6" -name "*.smali" | wc -l
ls -la "$APK/lib/arm64-v8a/" | grep pjsua
