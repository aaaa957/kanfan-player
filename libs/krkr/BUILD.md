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
