#!/usr/bin/env bash
#
# 看番 · KanFan —— APK 构建脚本
#
# 不依赖 Gradle / Android Studio，只调用 SDK 里的原生工具链：
#   aapt2 → javac → d8 → zipalign → apksigner
# 在一台普通笔记本上，全量构建约 20 秒。
#
# 用法：
#   ANDROID_SDK_ROOT=/path/to/android-sdk bash build.sh
#   # 或把 SDK 放在仓库内的 ./sdk 下，直接 bash build.sh
#
# 可覆盖的环境变量：
#   ANDROID_SDK_ROOT / ANDROID_HOME   SDK 根目录（默认 ./sdk）
#   VERSION_CODE / VERSION_NAME       覆盖清单里的版本号
#   KEYSTORE / KS_PASS / KS_ALIAS     签名密钥（默认 keystore/kanfan.jks）
#   MIN_SDK / TARGET_SDK              默认 23 / 34
#
set -euo pipefail
cd "$(dirname "$0")"

SDK="${ANDROID_SDK_ROOT:-${ANDROID_HOME:-$PWD/sdk}}"
MIN_SDK="${MIN_SDK:-23}"
TARGET_SDK="${TARGET_SDK:-34}"
OUT="out"
APP="app"
KEYSTORE="${KEYSTORE:-keystore/kanfan.jks}"
KS_PASS="${KS_PASS:-kanfan123}"
KS_ALIAS="${KS_ALIAS:-kanfan}"

# 原生工具读不懂 Git Bash 的 /d/... 路径，统一转成 Windows 风格（Linux/macOS 上原样返回）
winpath() {
  if command -v cygpath >/dev/null 2>&1; then cygpath -w "$1"; else printf '%s' "$1"; fi
}

# ---- 定位 JDK ----
if ! command -v javac >/dev/null 2>&1; then
  if [ -n "${JAVA_HOME:-}" ] && [ -x "$JAVA_HOME/bin/javac" ]; then
    PATH="$JAVA_HOME/bin:$PATH"
  fi
fi
command -v javac >/dev/null 2>&1 || {
  echo "找不到 javac。请安装 JDK 8 及以上，并把 bin 目录加入 PATH 或设置 JAVA_HOME。"
  exit 1
}

# ---- 定位 build-tools（取版本号最大的一个）----
BT=""
for d in $(ls -d "$SDK"/build-tools/* "$SDK"/build-tools-* "$SDK"/android-* 2>/dev/null | sort -V); do
  if [ -f "$d/aapt2" ] || [ -f "$d/aapt2.exe" ]; then BT="$d"; fi
done
[ -n "$BT" ] || {
  echo "找不到 build-tools（需要含 aapt2 的目录）。"
  echo "请设置 ANDROID_SDK_ROOT，或把 SDK 放到 ./sdk 下。"
  exit 1
}

# ---- 定位 platform（android.jar）----
PLATFORM=""
for j in $(ls "$SDK"/platforms/*/android.jar "$SDK"/android-*/android.jar 2>/dev/null | sort -V); do
  PLATFORM="$j"
done
[ -n "$PLATFORM" ] || { echo "找不到 android.jar，请安装 platform $TARGET_SDK"; exit 1; }

# ---- 版本号默认取 AndroidManifest.xml ----
VERSION_CODE="${VERSION_CODE:-$(grep -o 'android:versionCode="[0-9]*"' "$APP/AndroidManifest.xml" | grep -o '[0-9]*' | head -1)}"
VERSION_NAME="${VERSION_NAME:-$(grep -o 'android:versionName="[^"]*"' "$APP/AndroidManifest.xml" | sed 's/.*="//; s/"$//' | head -1)}"

echo "SDK        : $SDK"
echo "build-tools: $BT"
echo "platform   : $PLATFORM"
echo "version    : $VERSION_NAME ($VERSION_CODE)"
echo

rm -rf "$OUT"
mkdir -p "$OUT/gen" "$OUT/classes" "$OUT/dex"

AAPT2="$BT/aapt2"; [ -f "$BT/aapt2.exe" ] && AAPT2="$BT/aapt2.exe"
D8="$BT/d8"; [ -f "$BT/d8.bat" ] && D8="$BT/d8.bat"
ZIPALIGN="$BT/zipalign"; [ -f "$BT/zipalign.exe" ] && ZIPALIGN="$BT/zipalign.exe"
APKSIGNER="$BT/apksigner"; [ -f "$BT/apksigner.bat" ] && APKSIGNER="$BT/apksigner.bat"

echo "==> 1/6 编译资源"
"$AAPT2" compile --dir "$APP/res" -o "$OUT/res.zip"

echo "==> 2/6 链接资源 + 生成 R.java"
"$AAPT2" link \
  -o "$OUT/base.apk" \
  -I "$(winpath "$PLATFORM")" \
  --manifest "$APP/AndroidManifest.xml" \
  -R "$OUT/res.zip" \
  --java "$OUT/gen" \
  --min-sdk-version "$MIN_SDK" \
  --target-sdk-version "$TARGET_SDK" \
  --version-code "$VERSION_CODE" \
  --version-name "$VERSION_NAME" \
  --auto-add-overlay

echo "==> 3/6 编译 Java"
find "$APP/java" "$OUT/gen" -name "*.java" > "$OUT/src.txt"
# -parameters 不是可选项：JDK 21 起会给内部类构造函数写出「名字索引为 0」的 MethodParameters 属性，
# 旧版 d8 读到时会对空名调用 length() 直接抛 NPE。加上它参数就有真实名字，d8 便能正常解析。
javac -source 8 -target 8 -encoding UTF-8 -nowarn -parameters \
  -bootclasspath "$(winpath "$PLATFORM")" \
  -classpath "$(winpath "$PWD/libs/jlibtorrent.jar")" \
  -d "$OUT/classes" @"$OUT/src.txt" \
  > "$OUT/javac.log" 2>&1 || { echo "javac 失败："; cat "$OUT/javac.log"; exit 1; }
echo "    生成 $(find "$OUT/classes" -name '*.class' | wc -l) 个 class"

echo "==> 4/6 dex（d8 只接受文件列表，不接受目录）"
find "$OUT/classes" -name "*.class" > "$OUT/classes.txt"
"$D8" --min-api "$MIN_SDK" --lib "$(winpath "$PLATFORM")" --output "$OUT/dex"   @"$OUT/classes.txt" "$(winpath "$PWD/libs/jlibtorrent.jar")" \
  > "$OUT/d8.log" 2>&1 || { echo "d8 失败："; head -c 2000 "$OUT/d8.log"; exit 1; }
[ -f "$OUT/dex/classes.dex" ] || { echo "d8 未产出 classes.dex"; cat "$OUT/d8.log"; exit 1; }

echo "==> 5/6 把 classes.dex 与原生库塞进 APK"
python - "$OUT" "$(pwd)" <<'PY'
import sys, os, shutil, zipfile
out = sys.argv[1]
root = sys.argv[2]
shutil.copyfile(os.path.join(out, "base.apk"), os.path.join(out, "unsigned.apk"))
with zipfile.ZipFile(os.path.join(out, "unsigned.apk"), "a", zipfile.ZIP_DEFLATED) as z:
    z.write(os.path.join(out, "dex", "classes.dex"), "classes.dex")
    print("   已写入 classes.dex")
    # 原生解压库：native/out/<abi>/libkanfan.so -> lib/<abi>/libkanfan.so
    nat = os.path.join(root, "native", "out")
    count = 0
    if os.path.isdir(nat):
        for abi in sorted(os.listdir(nat)):
            so = os.path.join(nat, abi, "libkanfan.so")
            if os.path.isfile(so):
                z.write(so, "lib/%s/libkanfan.so" % abi)
                print("   已写入 lib/%s/libkanfan.so (%.0f KB)" % (abi, os.path.getsize(so) / 1024))
                count += 1
    if count == 0:
        print("   （没有原生库，只打 Java 部分）")
    # BT 引擎：libs/jni/<abi>/libjlibtorrent.so -> lib/<abi>/
    jt = os.path.join(root, "libs", "jni")
    if os.path.isdir(jt):
        for abi in sorted(os.listdir(jt)):
            so = os.path.join(jt, abi, "libjlibtorrent-1.2.0.18.so")
            if os.path.isfile(so):
                z.write(so, "lib/%s/libjlibtorrent-1.2.0.18.so" % abi)
                print("   已写入 lib/%s/libjlibtorrent-1.2.0.18.so (%.1f MB)" % (abi, os.path.getsize(so) / 1048576.0))
PY

echo "==> 6/6 对齐 + 签名"
"$ZIPALIGN" -f 4 "$OUT/unsigned.apk" "$OUT/aligned.apk"

if [ ! -f "$KEYSTORE" ]; then
  echo "   未找到签名密钥，生成一个新的：$KEYSTORE"
  mkdir -p "$(dirname "$KEYSTORE")"
  keytool -genkeypair -keystore "$KEYSTORE" -alias "$KS_ALIAS" \
    -keyalg RSA -keysize 2048 -validity 10000 \
    -storepass "$KS_PASS" -keypass "$KS_PASS" \
    -dname "CN=Kanfan Player, OU=Personal, O=Kanfan, L=CN, ST=CN, C=CN" > /dev/null 2>&1
  echo "   注意：请备份该密钥。换钥匙后新 APK 无法覆盖安装，只能先卸载旧版。"
fi

"$APKSIGNER" sign \
  --ks "$KEYSTORE" --ks-pass "pass:$KS_PASS" --key-pass "pass:$KS_PASS" \
  --ks-key-alias "$KS_ALIAS" --out "$OUT/kanfan.apk" "$OUT/aligned.apk"

"$APKSIGNER" verify "$OUT/kanfan.apk" && echo "   签名校验通过"
ls -la "$OUT/kanfan.apk" | awk '{printf "   APK 大小 %.0f KB\n", $5/1024}'
echo "构建完成 -> $OUT/kanfan.apk"
