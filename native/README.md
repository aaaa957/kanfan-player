# native/

7z 与 rar 的解压靠两个第三方 C/C++ 工程编译成的 `libkanfan.so`：

| 来源 | 版本 | 用途 |
|---|---|---|
| [7-Zip](https://www.7-zip.org/) | 26.05（`7z2605-src.7z`） | 7z 解码器只看 `C/` 里的 C 代码 |
| [unrar](https://www.rarlab.com/rar_add.htm) | 7.1.6（`unrarsrc-7.1.6.tar.gz`） | rar / rar5，用它的 DLL 接口 |

## 构建

1. 装 Android NDK（r26 及以上），设 `ANDROID_NDK_HOME`
2. 把上面的源码解压到任意目录
3. 指定源码路径并编译：

```bash
SEVENZ_SRC=/path/to/7zsrc UNRAR_SRC=/path/to/unrar \
ANDROID_NDK_HOME=/path/to/ndk \
bash native/build_native.sh all
```

产物在 `native/out/<abi>/libkanfan.so`，`build.sh` 会自动塞进 APK。
没编原生库也能打出 APK，只是 7z/rar 会提示「原生库没能加载」。

## 几个必须知道的点

- **unrar 的 `dll.hpp` 要先定义 `_UNIX`**，它才会补上 `UINT`/`LPARAM`/`HANDLE`；
  但定义完要 `#undef UINT` / `#undef LONG` / `#undef LPARAM`，否则和 7-Zip 的 typedef 撞车。
- **Android 上没有 `lutimes`**，编译 unrar 的 `extinfo.cpp` 要加 `-Dlutimes=utimes`。
- **32 位 ABI 没有 `fseeko`/`ftello`**，所以 7z 的输入流改用 `fd` + `pread64`。
- **必须 `-static-libstdc++`**，否则 `.so` 会依赖 `libc++_shared.so`，而 APK 里没有它，
  真机一加载就是 `UnsatisfiedLinkError`。

## 许可

unrar 的源码许可禁止用它做出 RAR 压缩器，解压是允许的；7-Zip 的 `C/` 是 public domain。
两者都是动态链接进本应用，不改变本应用自身的 MIT 许可。
