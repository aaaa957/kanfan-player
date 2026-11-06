# ONS 引擎（libonscripter.so / libsdl.so）构建说明

上游：https://github.com/matthewn4444/onscripter-engine-android
提交：`523b97af718015bb108c99b8d2ec5e628755a2f9`（master）
许可：GPL-2.0-or-later

## 为什么在仓库里放 .so

这份引擎是 C/C++（ONScripter + SDL2 + SDL_image/ttf/mixer + freetype + libpng + libjpeg
+ bzip2 + libmad + tremor + lua），完整源码约 46 MB。仓库只放编译产物与补丁，
源码按上面地址取同一提交即可复现。

## 复现步骤

1. 取源码 tarball：

       curl -L https://codeload.github.com/matthewn4444/onscripter-engine-android/tar.gz/523b97af718015bb108c99b8d2ec5e628755a2f9 | tar xz

2. 补 Lua（上游是 git submodule，tarball 里是空的）：

       curl -L https://www.lua.org/ftp/lua-5.4.6.tar.gz | tar xz
       cp lua-5.4.6/src/*.c lua-5.4.6/src/*.h <src>/src/main/cpp/lua/src/

3. 打两个补丁（见 `patches/`，NDK r26 的 clang 会把这两处当错误）：

       cd <src> && git apply /path/to/libs/ons/patches/*.patch

4. 配置 + 编译（只编 arm64）：

       cmake -S <src> -B build -G Ninja          -DCMAKE_TOOLCHAIN_FILE=$NDK/build/cmake/android.toolchain.cmake          -DANDROID_ABI=arm64-v8a -DANDROID_PLATFORM=android-21          -DANDROID_ARM_NEON=TRUE -DCMAKE_BUILD_TYPE=Release
       ninja -C build

5. 产物（复制到 `libs/ons/arm64-v8a/`）：

       build/libonscripter.so     # 9.8 MB
       build/sdl/libsdl.so       # 2.8 MB

## 备注

- `libonscripter.so` 的 `NEEDED` 只有系统库 + `libsdl.so`，两个都要打进 APK 的 `lib/arm64-v8a/`
- Java 层（`app/java/com/onscripter/`）原样取自同一提交，只额外补了
  `androidx.annotation.{Keep,NonNull,Nullable}` 三个占位注解
- 只编了 arm64；32 位设备在 `OnsActivity` 里会直接提示不支持
