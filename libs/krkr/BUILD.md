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

---

## v3.8 追加：让引擎从**外部目录**启动游戏

krkrsdl2 的 SDL 分支原本只支持「游戏打包进 assets」，从命令行传目录这条路是坏的。
在真机/模拟器上表现为「点击启动 → 一闪 → 回桌面」。完整补丁见
`patches/0001-android-external-project-dir.patch`，共 5 处：

### 1) 项目目录改用 `stat()` 判定（`SysInitImpl.cpp`）

原来的兜底逻辑用 `TVPCheckExistentLocalFolder()` 判目录，它走 TJS 存储系统，
对裸的 `/sdcard/...`、`/data/data/...` **一律返回 false**，于是退回 `getcwd()`。
Android 上 cwd 是 `/data`，引擎就去 `/data` 找 `startup.tjs`，必然 `Cannot find storage`。

### 2) 项目目录 / xp3 / xp3 子目录 都要 `TVPAddAutoPath`

整个源码里 `TVPAddAutoPath` **只被插件桩导出过，没有任何地方注册项目目录**，
所以 `Rebuilding Auto Path Table` 永远是 `Total 0 file(s) found`。

补法是：项目目录注册一次；每个 `*.xp3` 注册成 `xxx.xp3>`；
再用 `tTVPXP3Archive` 枚举 xp3 内的目录，逐个注册 `xxx.xp3>system/` 这种。
（`TVPAddAutoPath("x.xp3>")` 只列**顶层**文件，`TVPRebuildAutoPathTable` 对含 `/` 的名字直接跳过。）

### 3) 脚本编码回退 CP932（`TextStream.cpp` + `sjis_table.h`）

老游戏的 `startup.tjs` / `Initialize.tjs` 是 Shift-JIS。原来的解码只认
`UTF-8` 和 `Shift_JIS`，而后者用的是系统 `mbrtowc`（Android 上是 UTF-8），
所以日文一出现就抛 `Cannot convert given narrow string to wide string`。

补法：UTF-8 解失败时用 `sjis_table.h`（CP932→UTF-16，Python 生成，9604 项）重解。

### 4) `ExePath()` 返回项目目录（`Application.cpp`）

Android 没有 `/proc/self/exe`，`ExePath()` 会退化成 `argv[0]` = `"app_process"`，
于是 `System.exePath` 变成 `file://./`。脚本里 `System.exePath + "system/bin/video.xp3"`
就被拼成 `file://./system/bin/video.xp3` —— 而 `file://./` 的 `.` 是 **host**，
后面的路径按**绝对路径**解析，与 cwd 无关，必然找不到。

### 5) 新增 `external/krdiag`

崩溃信号处理（SIGSEGV/SIGABRT/… + `_Unwind_Backtrace`，只记模块名和模块内偏移，
配未 strip 的 `.so` 用 `llvm-addr2line` 还原）、分段打点、SDL_Log 重定向。
日志落在 `Android/data/<包名>/files/diag/krkr-native.log`，用户能直接看到。

### 另外两处（非引擎）

- **`system/bin` 软链**：部分「kirikiroid 专版」把 `system/bin/*.xp3` 提到根目录，
  脚本仍按老路径加载。`KrkrActivity.prepareLayout()` 给根目录每个 `.xp3` 在
  `system/bin/` 下建软链（失败退硬链）。
- **调试入口**：`am start ... --es krkr <目录>` 可跳过 SAF 直接进引擎，便于自动化。
