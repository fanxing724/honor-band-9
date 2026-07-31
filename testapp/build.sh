#!/bin/bash
set -e
SDK=/mnt/workspace/reverse-honor/android-sdk
BT=$SDK/build-tools/34.0.0
PLAT=$SDK/platforms/android-34
ANDROID_JAR=$PLAT/android.jar
AAPT2=$BT/aapt2
D8=$BT/d8
ZIPALIGN=$BT/zipalign
APKSIGNER=$BT/apksigner
KEYTOOL=/usr/lib/jvm/java-17-openjdk-amd64/bin/keytool

APP=/mnt/workspace/reverse-honor/testapp
SRC=$APP/src
RES=$APP/res
MANIFEST=$APP/AndroidManifest.xml
OUT=$APP/build
mkdir -p $OUT/res $OUT/gen $OUT/classes $OUT/dex

echo "== 1. aapt2 compile resources =="
$AAPT2 compile --dir $RES -o $OUT/res/res.zip
echo "   done"

echo "== 2. aapt2 link (gen R.java + res apk) =="
$AAPT2 link --no-auto-version --auto-add-overlay \
    -I $ANDROID_JAR \
    --java $OUT/gen \
    -o $OUT/app-res.apk \
    --manifest $MANIFEST \
    -R $OUT/res/res.zip
echo "   R.java + app-res.apk done"

echo "== 3. javac compile =="
javac -source 1.8 -target 1.8 -cp "$ANDROID_JAR:$OUT/gen" \
    -d $OUT/classes \
    $(find $SRC -name "*.java") $(find $OUT/gen -name "*.java")
echo "   classes done"

echo "== 4. d8 desugar + dex =="
$D8 --min-api 21 --lib $ANDROID_JAR --output $OUT/dex $(find $OUT/classes -name "*.class")
echo "   dex done"

echo "== 5. add classes.dex into apk =="
cp $OUT/app-res.apk $OUT/app-nodef.apk
cd $OUT/dex && zip -u $OUT/app-nodef.apk classes.dex && cd $APP

echo "== 6. zipalign =="
$ZIPALIGN -f 4 $OUT/app-nodef.apk $OUT/app-unsigned.apk

echo "== 7. keystore =="
if [ ! -f $APP/debug.keystore ]; then
  $KEYTOOL -genkeypair -v -keystore $APP/debug.keystore \
    -alias androiddebugkey -keyalg RSA -keysize 2048 -validity 10000 \
    -storepass android -keypass android \
    -dname "CN=Android Debug,O=HonorTest"
fi

echo "== 8. apksigner =="
$APKSIGNER sign --ks $APP/debug.keystore --ks-pass pass:android \
    --key-pass pass:android --out $APP/honor-band-test.apk $OUT/app-unsigned.apk

echo "== DONE =="
ls -la $APP/honor-band-test.apk
$APKSIGNER verify --print-certs $APP/honor-band-test.apk 2>&1 | head -5
