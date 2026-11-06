#!/usr/bin/env bash
# 编译原生解压库（unrar + 7-Zip C 解码器）
# 用法：bash native/build_native.sh [arm64-v8a|armeabi-v7a|all]
set -u

cd "$(dirname "$0")/.."
ROOT="$(pwd)"
NDK="${ANDROID_NDK_HOME:-/d/tmp/ndk/android-ndk-r26d}"
SRC_7Z="${SEVENZ_SRC:-/d/tmp/ndk/7zsrc}"
SRC_RAR="${UNRAR_SRC:-/d/tmp/ndk/unrar_src/unrar}"
OUT="$ROOT/native/out"

HOST="$NDK/toolchains/llvm/prebuilt/windows-x86_64/bin"
CC="$HOST/clang.exe"
CXX="$HOST/clang++.exe"

if [ ! -x "$CXX" ]; then
  echo "找不到 NDK 编译器：$CXX"
  exit 1
fi
if [ ! -f "$SRC_7Z/C/7zDec.c" ]; then
  echo "找不到 7-Zip 源码：$SRC_7Z/C/7zDec.c"
  exit 1
fi
if [ ! -f "$SRC_RAR/dll.cpp" ]; then
  echo "找不到 unrar 源码：$SRC_RAR/dll.cpp"
  exit 1
fi

# 7z 需要的最小编译器集合
OBJ_7Z="7zDec LzmaDec Lzma2Dec Bcj2 Delta Bra Bra86 BraIA64 CpuArch Alloc 7zCrc 7zStream"
# unrar 的全部对象（makefile 的 OBJECTS + LIB_OBJ）
OBJ_RAR="rar strlist strfn pathfn smallfn global file filefn filcreat archive arcread unicode system crypt crc rawread encname resource match timefn rdwrfn consio options errhnd rarvm secpassword rijndael getbits sha1 sha256 blake2s hash extinfo extract volume list find unpack headers threadpool rs16 cmddata ui largepage filestr scantree dll qopen"

build_abi() {
  local ABI="$1" TARGET="$2"
  local DIR="$OUT/$ABI"
  rm -rf "$DIR"; mkdir -p "$DIR"
  echo "==> $ABI ($TARGET)"

  local CF="-O2 -fPIC -D_FILE_OFFSET_BITS=64 -D_LARGEFILE_SOURCE -DRAR_SMP -DRARDLL -Dlutimes=utimes -Wno-everything"

  for f in $OBJ_7Z; do
    "$CC" --target="$TARGET" $CF -I"$SRC_7Z/C" -c "$SRC_7Z/C/$f.c" -o "$DIR/7z_$f.o" 2>"$DIR/err_$f.txt"
    [ -f "$DIR/7z_$f.o" ] || { echo "  ✗ 7z/$f"; head -4 "$DIR/err_$f.txt"; return 1; }
  done

  for f in $OBJ_RAR; do
    "$CXX" --target="$TARGET" $CF -std=c++11 -I"$SRC_RAR" -c "$SRC_RAR/$f.cpp" -o "$DIR/rar_$f.o" 2>"$DIR/err_rar_$f.txt"
    [ -f "$DIR/rar_$f.o" ] || { echo "  ✗ rar/$f"; head -4 "$DIR/err_rar_$f.txt"; return 1; }
  done

  "$CXX" --target="$TARGET" $CF -std=c++11 -I"$SRC_RAR" -I"$SRC_7Z/C" \
    -c "$ROOT/native/kanfan_native.cpp" -o "$DIR/kanfan_native.o" 2>"$DIR/err_native.txt"
  [ -f "$DIR/kanfan_native.o" ] || { echo "  ✗ kanfan_native.cpp"; head -20 "$DIR/err_native.txt"; return 1; }

  # -static-libstdc++：把 libc++ 静态链进来，免得 APK 还得再带一个 libc++_shared.so
  "$CXX" --target="$TARGET" -shared -static-libstdc++ -o "$DIR/libkanfan.so"     "$DIR"/*.o -llog -lm 2>"$DIR/err_link.txt"
  [ -f "$DIR/libkanfan.so" ] || { echo "  ✗ 链接失败"; head -20 "$DIR/err_link.txt"; return 1; }

  local SZ
  SZ=$(stat -c %s "$DIR/libkanfan.so" 2>/dev/null || echo 0)
  echo "  ✓ libkanfan.so  $((SZ / 1024)) KB"
  "$HOST/llvm-strip.exe" --strip-unneeded "$DIR/libkanfan.so" 2>/dev/null
  SZ=$(stat -c %s "$DIR/libkanfan.so" 2>/dev/null || echo 0)
  echo "  ✓ strip 后      $((SZ / 1024)) KB"
}

WHICH="${1:-all}"
case "$WHICH" in
  arm64-v8a)   build_abi arm64-v8a aarch64-linux-android21 ;;
  armeabi-v7a) build_abi armeabi-v7a armv7a-linux-androideabi21 ;;
  all)
    build_abi arm64-v8a aarch64-linux-android21 &&
    build_abi armeabi-v7a armv7a-linux-androideabi21
    ;;
  *) echo "未知 ABI：$WHICH"; exit 1 ;;
esac
