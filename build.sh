#!/bin/bash
set -e
T=/home/claude/tools
cd /home/claude/gamelog
rm -rf out && mkdir -p out/classes out/apk
$T/aapt2_64 compile --dir res -o out/res.zip
$T/aapt2_64 link -o out/base.apk -I $T/android.jar --manifest AndroidManifest.xml \
  --min-sdk-version 26 --target-sdk-version 33 --version-code 11 --version-name 1.10 out/res.zip
javac -source 8 -target 8 -encoding UTF-8 -Xlint:-options -bootclasspath $T/android.jar \
  -d out/classes $(find src -name '*.java')
java -cp $T/dx-30.0.2.jar com.android.dx.command.Main --dex --min-sdk-version=26 \
  --output=out/classes.dex out/classes
cp out/base.apk out/apk/gamelog-unsigned.apk
(cd out && zip -q -j apk/gamelog-unsigned.apk classes.dex)
java -jar /home/claude/dl/uber-apk-signer-1.3.0.jar --apks out/apk/gamelog-unsigned.apk --out out/signed
ls -la out/signed
