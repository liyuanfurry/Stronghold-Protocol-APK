#!/data/data/com.dsharnessmobile.shell/files/usr/bin/bash
#
# Builds StrongholdProtocol.apk without Gradle.
#
#   aapt2 compile -> aapt2 link --output-to-dir -> javac -> d8 -> make_apk.py -> apksigner
#
# make_apk.py replaces `zipalign` (not packaged in Termux); see its header for why that matters.
#
set -euo pipefail

HERE="$(cd "$(dirname "$0")" && pwd)"
export PATH="/data/data/com.dsharnessmobile.shell/files/usr/bin:$PATH"

ANDROID_JAR="${ANDROID_JAR:-$HERE/sdkroot/android-35/android.jar}"
SRC="$HERE/android"
OUT="$HERE/out"
MIN_SDK="${MIN_SDK:-21}"
TARGET_SDK="${TARGET_SDK:-34}"
VERSION_CODE=1
VERSION_NAME="1.6"

KEYSTORE="$HERE/keys/stronghold.keystore"
KS_PASS="stronghold"
KS_ALIAS="stronghold"

[ -f "$ANDROID_JAR" ] || { echo "缺少 android.jar: $ANDROID_JAR" >&2; exit 1; }

echo "==> 清理"
rm -rf "$OUT"
mkdir -p "$OUT/compiled" "$OUT/gen" "$OUT/classes" "$OUT/dex" "$OUT/contents"

echo "==> 1/6 编译资源 (aapt2 compile)"
aapt2 compile --dir "$SRC/res" -o "$OUT/compiled/res.zip"

echo "==> 2/6 链接资源与清单 (aapt2 link)"
aapt2 link \
    --output-to-dir -o "$OUT/contents" \
    -I "$ANDROID_JAR" \
    --manifest "$SRC/AndroidManifest.xml" \
    --java "$OUT/gen" \
    --min-sdk-version "$MIN_SDK" \
    --target-sdk-version "$TARGET_SDK" \
    --version-code "$VERSION_CODE" \
    --version-name "$VERSION_NAME" \
    "$OUT/compiled/res.zip"

echo "==> 3/6 编译 Java (javac)"
find "$SRC/java" "$OUT/gen" -name '*.java' > "$OUT/sources.txt"
javac -nowarn --release 8 \
    -classpath "$ANDROID_JAR" \
    -d "$OUT/classes" \
    @"$OUT/sources.txt"

echo "==> 4/6 转 DEX (d8)"
jar cf "$OUT/classes.jar" -C "$OUT/classes" .
d8 --min-api "$MIN_SDK" --lib "$ANDROID_JAR" --output "$OUT/dex" "$OUT/classes.jar"
cp "$OUT/dex/classes.dex" "$OUT/contents/classes.dex"

echo "==> 5/6 打包并对齐 (make_apk.py)"
# android/assets/ is the pre-installed media payload; make_apk.py packs it under assets/ without
# copying it into the staging directory first.
if [ -d "$SRC/assets/node" ]; then
    echo "    生成本机服务器载荷清单"
    python3 "$HERE/make_payload_list.py" "$SRC/assets"
fi
if [ -f "$SRC/assets/baseline/index.txt" ]; then
    echo "    素材包: $(find "$SRC/assets/mirror" -type f | wc -l) 个文件, $(du -sh "$SRC/assets" | cut -f1)"
    python3 "$HERE/make_apk.py" "$OUT/contents" "$OUT/unsigned.apk" "$SRC/assets"
else
    echo "    ! android/assets 不存在，将生成不含素材的小包" >&2
    python3 "$HERE/make_apk.py" "$OUT/contents" "$OUT/unsigned.apk"
fi

echo "==> 6/6 签名 (apksigner)"
mkdir -p "$(dirname "$KEYSTORE")"
if [ ! -f "$KEYSTORE" ]; then
    echo "    生成自签名密钥库"
    keytool -genkeypair -v \
        -keystore "$KEYSTORE" -alias "$KS_ALIAS" \
        -keyalg RSA -keysize 2048 -validity 10950 \
        -storepass "$KS_PASS" -keypass "$KS_PASS" \
        -dname "CN=Stronghold Protocol Android, OU=Fan client, O=Non-commercial, C=CN" >/dev/null
fi

# Bound the signer's heap: it re-reads the whole archive (270+ MiB of assets) and the phone may not
# have 4 GiB to hand out.
JAVA_TOOL_OPTIONS="-Xmx2g" apksigner sign \
    --ks "$KEYSTORE" --ks-key-alias "$KS_ALIAS" \
    --ks-pass "pass:$KS_PASS" --key-pass "pass:$KS_PASS" \
    --min-sdk-version "$MIN_SDK" \
    --out "$HERE/StrongholdProtocol-$VERSION_NAME.apk" \
    "$OUT/unsigned.apk"

echo
echo "==> 校验"
apksigner verify --verbose --print-certs "$HERE/StrongholdProtocol-$VERSION_NAME.apk" | head -20
ls -la "$HERE/StrongholdProtocol-$VERSION_NAME.apk"
echo "完成: $HERE/StrongholdProtocol-$VERSION_NAME.apk"
