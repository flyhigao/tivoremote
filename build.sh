#!/bin/bash
set -e

BUILD_TOOLS="E:/Android/sdk/build-tools/33.0.2"
PLATFORM="E:/Android/sdk/platforms/android-33/android.jar"
JAVA_BIN="E:/Program Files/Java/jdk-17/bin"
KEYSTORE="C:/Users/haha/.android/debug.keystore"
BASE_DIR="E:/esp/project/TiVoRemote"

rm -rf "$BASE_DIR/build"
mkdir -p "$BASE_DIR/build/compiled_res"
mkdir -p "$BASE_DIR/build/gen"
mkdir -p "$BASE_DIR/build/classes"
mkdir -p "$BASE_DIR/build/dex"

echo "1. Compiling resources..."
"$BUILD_TOOLS/aapt2.exe" compile --dir "$BASE_DIR/app/src/main/res" -o "$BASE_DIR/build/compiled_res/resources.zip"

echo "2. Linking resources..."
"$BUILD_TOOLS/aapt2.exe" link -o "$BASE_DIR/build/unaligned.apk" \
  -I "$PLATFORM" \
  --manifest "$BASE_DIR/app/src/main/AndroidManifest.xml" \
  --java "$BASE_DIR/build/gen" \
  "$BASE_DIR/build/compiled_res/resources.zip" \
  --auto-add-overlay

echo "3. Compiling Java..."
"$JAVA_BIN/javac.exe" -encoding UTF-8 -d "$BASE_DIR/build/classes" \
  -cp "$PLATFORM" \
  "$BASE_DIR/build/gen/com/tivo/remote/R.java" \
  "$BASE_DIR/app/src/main/java/com/tivo/remote/"*.java

echo "4. Converting to DEX..."
"$JAVA_BIN/java.exe" -cp "$BUILD_TOOLS/lib/d8.jar" com.android.tools.r8.D8 \
  --output "$BASE_DIR/build/dex" \
  --lib "$PLATFORM" \
  "$BASE_DIR/build/classes/com/tivo/remote/"*.class

echo "5. Adding DEX into APK..."
cd "$BASE_DIR/build/dex"
"$JAVA_BIN/jar.exe" -uf "$BASE_DIR/build/unaligned.apk" classes.dex

echo "6. Aligning APK..."
"$BUILD_TOOLS/zipalign.exe" -f -p 4 \
  "$BASE_DIR/build/unaligned.apk" \
  "$BASE_DIR/build/TiVoRemote_aligned.apk"

echo "7. Signing APK..."
"$JAVA_BIN/java.exe" -jar "$BUILD_TOOLS/lib/apksigner.jar" sign \
  --ks "$KEYSTORE" \
  --ks-pass pass:android \
  --out "$BASE_DIR/TiVoRemote.apk" \
  "$BASE_DIR/build/TiVoRemote_aligned.apk"

echo "Build complete! Output: $BASE_DIR/TiVoRemote.apk"
