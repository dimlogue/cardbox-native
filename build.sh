#!/bin/bash
# 卡盒 · 原生版构建（手写管线：aapt2 + javac + d8 + apksigner，无 Gradle）
set -e
cd "$(dirname "$0")"

VER_CODE=285
VER_NAME="2.85-native"
PKG="com.igll.carddbnative"

SDK=~/workspace/android-sdk
BT=$SDK/build-tools/34.0.0
PLATFORM=$SDK/platforms/android-34/android.jar
[ -f "$PLATFORM" ] || { echo "FATAL: 找不到 $PLATFORM"; exit 1; }
AAPT2="$BT/aapt2"; AAPT="$BT/aapt"; D8="$BT/d8"; ALIGN="$BT/zipalign"; SIGNER="$BT/apksigner"
JAVAC="$HOME/workspace/jdk/jdk-17.0.20.1+1/bin/javac"
export PATH="$HOME/workspace/jdk/jdk-17.0.20.1+1/bin:$PATH"
KS="$HOME/workspace/card-db/apk/build/manual/debug.keystore"
[ -f "$KS" ] || { echo "FATAL: keystore 缺失 $KS"; exit 1; }

OUT="$(mktemp -d)"
trap 'rm -rf "$OUT"' EXIT

echo "=== [1/6] 版本与数据校验 ==="
grep -q "versionCode=\"$VER_CODE\"" AndroidManifest.xml || { echo "FATAL: Manifest versionCode 不是 $VER_CODE"; exit 1; }
CARDS_N=$(python3 -c "import json;print(len(json.load(open('assets/data/cards.json',encoding='utf-8'))['cards']))")
[ "$CARDS_N" = "213" ] || { echo "FATAL: 卡片数 $CARDS_N ≠ 213"; exit 1; }
echo "版本 $VER_NAME ($VER_CODE)，卡片 $CARDS_N 张: OK"

echo "=== [2/6] 资产 staging+加密（Q170 E1）与 aapt2 compile/link ==="
# E1：源码树 assets/ 保持明文（数据发布流水线不动），只在 staging 树里把 data/**
# 逐文件 CBX1 加密后打包。工具与运行时共用同一份 DataCipher（同源同法），编译
# 在此做、产物只在临时目录；staging 全量解密回源比对过了才许进 aapt2。
STAGE="$OUT/staging-assets"
mkdir -p "$STAGE" "$OUT/cbxtools"
cp -r assets/. "$STAGE"/
"$JAVAC" -encoding UTF-8 -d "$OUT/cbxtools" \
  app/src/main/java/com/igll/carddbnative/DataCipher.java tools/cbx/CbxTool.java
java -cp "$OUT/cbxtools" CbxTool encrypt-tree "$STAGE/data"
java -cp "$OUT/cbxtools" CbxTool verify-tree "$STAGE/data" assets/data
mkdir -p "$OUT/compiled"
"$AAPT2" compile --dir res -o "$OUT/compiled" 2>&1 | grep -v '^$' || true
"$AAPT2" link -o "$OUT/base.apk" \
  -I "$PLATFORM" \
  --manifest AndroidManifest.xml \
  --java "$OUT/gen" \
  -A "$STAGE" \
  "$OUT"/compiled/*.flat 2>&1 | tail -2 || true
[ -f "$OUT/base.apk" ] || { echo "FATAL: link 失败"; exit 1; }

echo "=== [3/6] javac ==="
mkdir -p "$OUT/classes"
"$JAVAC" -encoding UTF-8 -source 8 -target 8 -cp "$PLATFORM" -d "$OUT/classes" \
  $(find app/src/main/java -name '*.java') $(find "$OUT/gen" -name 'R.java')
[ -f "$OUT/classes/com/igll/carddbnative/MainActivity.class" ] || { echo "FATAL: javac 无产物"; exit 1; }

echo "=== [4/6] d8 ==="
mkdir -p "$OUT/dex"
"$D8" --lib "$PLATFORM" --min-api 24 --output "$OUT/dex" "$OUT"/classes/com/igll/carddbnative/*.class 2>&1 | tail -1 || true
[ -f "$OUT/dex/classes.dex" ] || { echo "FATAL: d8 无产物"; exit 1; }

echo "=== [5/6] 组装 + zipalign + 签名 ==="
cp "$OUT/base.apk" "$OUT/app.apk"
(cd "$OUT/dex" && zip -q -j ../app.apk classes.dex)
"$ALIGN" -f 4 "$OUT/app.apk" "$OUT/aligned.apk"
"$SIGNER" sign --ks "$KS" --ks-pass pass:android --out "$OUT/carddb-native.apk" "$OUT/aligned.apk" 2>&1 | tail -1 || true
"$SIGNER" verify "$OUT/carddb-native.apk" || { echo "FATAL: 签名验证失败"; exit 1; }

echo "=== [6/6] 终检 ==="
"$AAPT" dump badging "$OUT/carddb-native.apk" | head -1
IMG=$(unzip -l "$OUT/carddb-native.apk" | grep -cE 'assets/data/images/[^/]+$' || true)
SRC_IMG=$(find assets/data/images -type f | wc -l | tr -d ' ')
[ "$IMG" = "$SRC_IMG" ] || { echo "FATAL: 包内图片数量异常($IMG ≠ 源 $SRC_IMG)"; exit 1; }
# Q170（E1）终检升级：包内 assets/data/** 全量验 CBX1 头（明文残留扫描）
# ＋随机抽样解密回源逐字节比对；不过不许出厂。
java -cp "$OUT/cbxtools" CbxTool verify-apk "$OUT/carddb-native.apk" assets 12
cp "$OUT/carddb-native.apk" ~/workspace/your_files/carddb-native.apk
ls -la ~/workspace/your_files/carddb-native.apk
echo "BUILD OK: $VER_NAME (versionCode=$VER_CODE)"
