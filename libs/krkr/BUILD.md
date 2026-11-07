# KiriKiri（krkrsdl2）构建说明

上游：https://github.com/uyjulian/krkrsdl2 （MIT）
内核：https://github.com/krkrz/krkrz （BSD 系）
提交：main 分支，递归拉子模块（`--recursive`，约 350 MB）

## 步骤

1. 克隆（子模块必须全，`external/krkrz`、`external/SDL`、`external/wuvorbis` 等）：

       git clone --recursive --depth 1 https://github.com/uyjulian/krkrsdl2.git

2. 先编 Android 版 SDL2（krkrsdl2 用 `find_package(SDL2)`）：

       cmake -S krkrsdl2/external/SDL -B sdlbuild -G Ninja \
         -DCMAKE_TOOLCHAIN_FILE=$NDK/build/cmake/android.toolchain.cmake \
         -DANDROID_ABI=arm64-v8a -DANDROID_PLATFORM=android-21 \
         -DCMAKE_INSTALL_PREFIX=<prefix> -DSDL_SHARED=ON -DSDL_STATIC=OFF
       ninja -C sdlbuild && cmake --install sdlbuild

   **要打一个补丁**：这份 SDL 会给 C 代码加 `-Werror=declaration-after-statement`，
   而 NDK r26 的 clang 默认 gnu17，Android 分支的源码大量「语句后声明」⇒ 直接编译失败。
   把 `CMakeLists.txt` 里那行改成 `-Wno-declaration-after-statement`。

3. 编 krkrsdl2：

       cmake -S krkrsdl2 -B krkbuild -G Ninja \
         -DCMAKE_TOOLCHAIN_FILE=$NDK/build/cmake/android.toolchain.cmake \
         -DANDROID_ABI=arm64-v8a -DANDROID_PLATFORM=android-21 \
         -DANDROID_STL=c++_static -DCMAKE_BUILD_TYPE=Release \
         -DSDL2_DIR=<prefix>/lib/cmake/SDL2
       ninja -C krkbuild

4. 产物（复制到 `libs/krkr/arm64-v8a/`）：

       krkbuild/libmain.so     # 43 MB 未剥离，strip 后 7.7 MB
       <prefix>/lib/libSDL2.so # 6.1 MB，strip 后 1.5 MB

## 运行方式

- `libmain.so` 导出 `SDL_main`，由 SDL2 的 `SDLActivity`（`org.libsdl.app`）加载并调用
- 参数是游戏目录的**真实路径**，所以 KRKR 游戏必须先导入到应用目录（见 `GameImport`）
- 只编了 arm64；32 位设备在片库里会提示不支持

---

## v3.7 追加：ffmpeg + 内建 krmovie

`libmain.so` 现在把 ffmpeg 静态链进去了（视频解码），体积 8.18 MB → 13.5 MB。

### 1) 编 ffmpeg（只留解码器，LGPL）

Windows 上没有 make，可以从 MSYS2 取一个独立的：

    curl -LO https://repo.msys2.org/msys/x86_64/make-4.4.1-2-x86_64.pkg.tar.zst
    tar --zstd -xf make-4.4.1-2-x86_64.pkg.tar.zst -C /tmp/makepkg
    export PATH=/tmp/makepkg/usr/bin:$PATH     # 配 Git Bash 自带的 msys-2.0.dll 就能跑

然后：

    ./configure --target-os=android --arch=aarch64 --cpu=armv8-a --enable-cross-compile \
      --cc=aarch64-linux-android21-clang --cxx=aarch64-linux-android21-clang++ \
      --ar=llvm-ar --ranlib=llvm-ranlib --strip=llvm-strip --nm=llvm-nm \
      --extra-cflags="-Wno-incompatible-function-pointer-types" \
      --disable-everything --disable-programs --disable-doc --disable-avdevice \
      --disable-postproc --disable-network --disable-encoders --disable-muxers --disable-filters \
      --enable-static --disable-shared --enable-pic \
      --enable-swscale --enable-swresample \
      --enable-decoder=h264,hevc,mpeg4,mpeg2video,vp8,vp9,theora,wmv3,wmv2,msmpeg4v3,flv,rv40,mjpeg,vorbis,mp3,aac,flac,pcm_s16le,ac3,eac3,opus \
      --enable-demuxer=mov,matroska,avi,asf,ogg,flv,mpegts,mpegps,mp3,wav,aac,flac \
      --enable-parser=h264,hevc,mpeg4video,vp8,vp9,vc1,mjpeg,vorbis,aac,opus \
      --enable-protocol=file,pipe --prefix=$PWD/out
    make -j8 && make install

**两个坑**：

- `--extra-cflags="-Wno-incompatible-function-pointer-types"` 不能省。ffmpeg 3.4 的
  `aarch64/h264dsp_init_aarch64.c` 把 `int` 宽度的函数指针赋给 `ptrdiff_t` 版本，
  NDK r26 的 clang 直接报错。
- 链接进 `.so` 时还要 `-Wl,--exclude-libs,ALL`：NEON 汇编（`vp9mc_neon.S`）对
  `ff_vp9_subpel_filters` 用 PC 相对引用，符号不本地化会报 `R_AARCH64_ADR_PREL_PG_HI21`。

### 2) 编 krkrsdl2

    cmake -S . -B krkbuild -G Ninja \
      -DCMAKE_TOOLCHAIN_FILE=$NDK/build/cmake/android.toolchain.cmake \
      -DANDROID_ABI=arm64-v8a -DANDROID_PLATFORM=android-21 -DANDROID_STL=c++_static \
      -DSDL2_DIR=<SDL2 安装前缀>/lib/cmake/SDL2 \
      -DFFMPEG_ROOT=<ffmpeg 安装前缀>
    ninja -C krkbuild

`-DFFMPEG_ROOT` 不传就自动关掉视频（`BUILTIN_KRKR_VIDEO=OFF`）。

### 3) 视频是怎么接进来的

引擎原来的 `src/core/visual/sdl2/VideoOvlImpl.cpp` 有 861/1582 行在 `#if 0` 里，
`Open()` 是空函数。现在补了：

- `Open()`：`TVPCreateStream` → `tTVPIStreamAdapter` → 内建播放器 → 两块 `tTVPBaseBitmap` → `SetVideoBuffer`
- 帧泵：`TVPAddContinuousEventHook` → 每帧 `GetFrontBuffer` → `AssignMainImage` → `Update`
- 播放器在 `external/krmovie/krmovie_player.cpp`，ffmpeg 解码 + SDL 音频

★ 约定：给播放器的缓冲是 `GetScanLine(height-1)`（**最后一行**），写入必须逐行倒序。
