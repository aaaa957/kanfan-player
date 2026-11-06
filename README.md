<div align="center">

<img src="docs/icon.png" width="110" alt="看番">

# 看番 · KanFan

**一个 53 KB 的安卓本地视频播放器**

纯 Java + 系统原生 API · 零第三方依赖 · 无广告 · 无会员 · 不联网

`Min SDK 23 (Android 6.0)` · `Target SDK 34` · `APK 53 KB`

</div>

---

## 为什么写这个

手机自带的视频播放器有三件事很难忍：

- **列表反人类**：把一部番的 25 集按「最近添加时间」乱序排，找第 12 集要滑半天
- **手势不顺手**：没有一个能调亮度的手势，晚上看片只能去拉系统亮度条
- **功能要会员**：倍速、记忆播放位置这类基础能力被锁在会员后面

于是用系统 API 自己写了一个：只做本地播放这一件事，但每一处都按「躺着看番」的姿势来。

它不解析任何网络资源、不上传任何统计、没有账号体系 —— 因为**它连 `INTERNET` 权限都没申请**。

---

## 功能

| 分类 | 能力 |
|---|---|
| **片库** | 手动添加文件夹（SAF 目录授权，持久化）；按文件夹分组；文件名自然排序（`2` 排在 `10` 前，`12 → 12.5 → 13` 正确）；显示缩略图、时长、体积、已看进度 |
| **播放** | 断点续播（退出 / 切后台自动记忆）；自动连播同系列下一集；最后一句提示「播放完毕」 |
| **手势** | 单击显隐控制条、双击播放/暂停、左右滑拖动进度、左半屏上下滑调亮度、右半屏上下滑调音量、长按 2× 临时倍速 |
| **倍速** | 0.5 / 0.75 / 1.0 / 1.25 / 1.5 / 2.0 六档，一键循环切换 |
| **画面** | 沉浸式全屏（隐藏状态栏 + 导航栏）、横屏、按原始比例居中不拉伸、播放时屏幕常亮 |
| **其它** | 锁定防误触；控制条 3.5 秒自动隐藏 |

### 手势一览

| 操作 | 效果 |
|---|---|
| 单击 | 显示 / 隐藏控制条 |
| **双击** | **播放 / 暂停** |
| 左右拖动 | 拖动进度（松手生效，带「目标时间 / 总时长 +2:10」预览） |
| 左半屏上下拖 | 亮度 |
| 右半屏上下拖 | 音量 |
| 长按不放 | 临时 2× 倍速，松手恢复 |
| 顶部锁图标 | 锁定，屏蔽全部手势 |

> 进度定位除了横向拖动，底部还有一条 2000 档精度的进度条，慢拖可以精确定位到秒。

### 隐私

`AndroidManifest.xml` 里只有一条权限：

```xml
<uses-permission android:name="android.permission.WAKE_LOCK" />
```

视频通过系统的 **Storage Access Framework** 访问 —— 只读你亲手选中的那个目录，连相册（MediaStore）都读不到。没有 `INTERNET`，物理上不可能联网。

---

## 架构

零第三方库（没有 AndroidX、没有 ExoPlayer、没有 Glide），全部用系统 API 拼出来：

| 文件 | 行数 | 职责 |
|---|---:|---|
| `PlayerActivity.java` | 950 | 播放器本体：MediaPlayer + SurfaceView、手势分发、连播、进度记忆、控制条 |
| `MainActivity.java` | 635 | 片库：目录授权、递归扫描、分组、缩略图异步加载 |
| `Scan.java` | 200 | SAF 目录遍历，产出 `VideoItem` 列表 |
| `Ui.java` | 125 | dp 换算、圆角/圆形背景、时长格式化、标题清洗 |
| `Store.java` | 31 | SharedPreferences：播放位置、已添加目录 |
| `VideoItem.java` | 17 | 数据模型 |

**为什么不用 ExoPlayer**：本地播放只需要 H.264/H.265 + AAC，系统 `MediaPlayer` 完全够用。放弃 ExoPlayer 换来的是 APK 从「十几 MB」降到 **53 KB**，启动零依赖加载。

---

## 构建

不需要 Gradle，也不需要 Android Studio。直接调 SDK 的原生工具链：

```
aapt2 compile → aapt2 link → javac → d8 → zipalign → apksigner
```

在一台普通笔记本上，全量构建约 **20 秒**。

### 前置

- **JDK 8+**（实测 8 / 17 / 21 均可）
- **Android SDK**：`build-tools`（含 aapt2/d8）+ `platform`（`android.jar`）
- **Python 3**（构建脚本用它把 `classes.dex` 塞进 APK）

没有 SDK 的话，两个包直连 Google 就能下：

```bash
curl -O https://dl.google.com/android/repository/build-tools_r34-windows.zip
curl -O https://dl.google.com/android/repository/platform-34-ext7_r01.zip
# 注意：platform-34_r03 / _r02 / _r01 都是 404，只有 -ext7_r01 是 200
```

### 构建

```bash
# 1) SDK 放在仓库内的 ./sdk 下
#    期望结构：sdk/build-tools/<ver>/aapt2    sdk/platforms/android-34/android.jar
#    （手动解压的 zip 也可以，脚本会自动探测 sdk/android-*/ 这类目录）
bash build.sh

# 2) 或者显式指定 SDK 位置
ANDROID_SDK_ROOT=/path/to/android-sdk bash build.sh
```

产物：`out/kanfan.apk`（已 zipalign + 签名）。

可覆盖的环境变量：

| 变量 | 默认值 | 说明 |
|---|---|---|
| `ANDROID_SDK_ROOT` / `ANDROID_HOME` | `./sdk` | SDK 根目录 |
| `VERSION_CODE` / `VERSION_NAME` | 读 `AndroidManifest.xml` | 版本号 |
| `KEYSTORE` / `KS_PASS` / `KS_ALIAS` | `keystore/kanfan.jks` / `kanfan123` / `kanfan` | 签名密钥，不存在会自动生成 |

> **签名密钥务必自己备份。** 换一把钥匙后，新 APK 无法覆盖安装旧版，只能先卸载 —— 而卸载会清掉播放记录。仓库里不会提交密钥（`.gitignore` 已排除）。

---

## 踩过的坑

写这个项目的过程中撞到几个不太常见的坑，记下来省得下次再踩。

### 1. d8 被 JDK 21 生成的 class 搞崩（NPE）

现象：`javac` 明明 0 error、class 也生成了 46 个，`d8` 却抛

```
java.lang.NullPointerException: Cannot invoke "String.length()" because "<parameter1>" is null
  origin: out\classes\com\kanfan\player\MainActivity$1.class
```

排查方式：用 `javap -v` 把 JDK 17 和 JDK 21 编出的同一个 class 对拍，差异一目了然 —— JDK 21 多写了 `MethodParameters` 属性，且内部类构造函数的合成参数 **名字索引是 0（无名）**；旧版 d8 读取时没有判空，直接 NPE。

`-g:none` 无效（`MethodParameters` 不属于调试信息）。**解法是 `javac -parameters`**，让参数带上真实名字，d8 便能正常解析。

### 2. `@argfile` 里只能放相对路径

`javac` / `d8` 通过 `@src.txt` 读文件列表时，里面若写 Git Bash 风格的 `/d/tmp/...`，原生 Windows 程序读不懂，会**静默地编译出 0 个 class** 而不报错。

解法：`cd` 到工程目录，`find` 输出相对路径即可。

### 3. `d8` 不接受目录

必须传 `.class` 文件列表（`@classes.txt`）。传目录报 `Unsupported source file type`。

### 4. `The surface has been released`

`SurfaceView` 的绘图表面是异步创建的。在 `onCreate` 里就把 `holder` 交给 `MediaPlayer`，此时 Surface 还没就绪，直接抛异常。

解法：加 `surfaceReady` 状态位，`surfaceCreated()` 才置真；真正挂载放在 `attachSurface()` 里做三重校验（播放器非空、表面就绪、`surface.isValid()`），`onPrepared()` 与每次表面重建后再补挂一次。

### 5. 缩略图全是黑块

`MediaStore.loadThumbnail()` 取的是**第一帧**，而番剧开头基本都是黑场。

解法：改用 `MediaMetadataRetriever`，取片头 **1/8 时间点**的帧，再等比缩放居中裁切。

---

## 已知限制

- 依赖系统解码器：H.264 / H.265 + AAC 没问题；AC3 / DTS 等非常规音轨可能无声
- 不支持外挂字幕文件（当前片源的字幕都是内嵌/烧录的）
- 倍速在退出播放器后回到 1.0×，不做跨会话记忆
- 没有接入 MediaSession，锁屏与通知栏无控制，也没有画中画
- 只在个人的几台设备上手动验证过，没有自动化测试与 CI

---

## 许可

[MIT](LICENSE)
