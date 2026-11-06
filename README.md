<div align="center">

<img src="docs/icon.png" width="110" alt="看番">

# 看番 · KanFan

**一个 106 KB 的安卓本地媒体浏览器：视频 + 字幕弹幕 + 电子书 + 图片**

纯 Java + 系统原生 API · 零第三方依赖 · 无广告 · 无会员 · 不联网

`Min SDK 23 (Android 6.0)` · `Target SDK 34` · `APK 106 KB`

</div>

---

## 功能

| 分类 | 能力 |
|---|---|
| **片库** | 分层浏览：首屏只列「已添加的文件夹」，点进去才看里面的子文件夹与视频，可逐级返回；手动添加文件夹（SAF 目录授权，持久化）；文件名自然排序（`2` 排在 `10` 前，`12 → 12.5 → 13` 正确）；显示缩略图、时长、体积、已看进度；**管理页**可多选移除、长按拖动调整文件夹顺序（顺序会记住） |
| **播放** | 断点续播（退出 / 切后台自动记忆）；自动连播同系列下一集；最后一句提示「播放完毕」 |
| **手势** | 单击显隐控制条、双击播放/暂停、左右滑拖动进度、左半屏上下滑调亮度、右半屏上下滑调音量、长按 2× 临时倍速 |
| **倍速** | 0.5 / 0.75 / 1.0 / 1.25 / 1.5 / 2.0 六档，一键循环切换 |
| **外挂字幕** | 自动加载视频同名的 `.srt` / `.ass` / `.ssa` / `.vtt`，也可从任意位置手选；UTF-8 与 GBK 都能识别 |
| **外挂弹幕** | 自动加载同名 B 站 `.xml` 弹幕；`弹幕` 按钮里可换文件或手选 `.xml` / `.ass`（弹弹play 导出） |
| **看图** | 点文件夹里的 `jpg / png / webp / gif` 直接全屏查看；**左右切换（跟手拖拽）与连续下滑两种模式**；双指缩放、双击放大、放大后拖动 |
| **阅读** | 点文件夹里的 `.txt` / `.epub` 直接进阅读器；**分页 / 滚动两种模式自选**（分页左滑右滑翻页，滚动连续加载）；章节识别、目录跳转、字号与三种背景、进度记忆；EPUB 的插图随文显示 |
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

### 外挂字幕与弹幕

打开视频时会在**同一个文件夹**里自动找同名文件：

```
刀剑神域 01.mkv
刀剑神域 01.sc.ass     ← 自动当字幕加载
刀剑神域 01.xml        ← 自动当弹幕加载
```

匹配规则是「以视频文件名开头，紧跟 `.` `_` `-` 空格或括号」，所以 `01` 不会误吃到 `010`。

播放页底部有两个胶囊按钮：**字幕**、**弹幕**。点开可以关闭、切换同目录下的文件，或从任意位置手选（`ACTION_OPEN_DOCUMENT`）。当前生效时按钮会高亮。

| 格式 | 支持 | 不支持 |
|---|---|---|
| SRT / VTT | 全部（`<i>` 之类标签自动剥离） | — |
| ASS / SSA 字幕 | 时间轴 + 纯文本、`\N` 换行、`{\...}` 标签剥离 | 字体、字号、定位、特效、卡拉OK —— 统一按底部居中显示 |
| B站 XML 弹幕 | 滚动 / 顶部 / 底部三种模式、颜色、HTML 实体反转义 | 代码弹幕、BAS 等高级弹幕 |
| ASS 弹幕 | 时间轴 + 文本，识别 `\c&HBBGGRR&` 颜色 | 位置与动画，统一按滚动处理 |

弹幕只占画面上方 42%，不会压住底部字幕；渲染按帧重绘并直接从播放器读进度（不做时间累积），所以**拖动进度条后立刻对齐**，轨道分配保证同一条不会叠字。

字幕文件按 UTF-8 严格解码，失败自动退回 GBK —— 中文字幕大多是这两种。

### 阅读器（TXT / EPUB）

片库本身就是文件夹浏览器，所以点 `.txt` / `.epub` 和点视频一样：**手动点进去，只是打开的是阅读器**。

```
我的小说/
├─ 三体.txt          ← 点它进阅读器
└─ 轻小说/
   └─ 某轻小说.epub   ← 点它也进阅读器，插图随文显示
```

**TXT**

- 章节识别：`第X章 / 节 / 回 / 卷 / 篇`、`Chapter 12`、`12、标题`、`序章 / 楔子 / 番外 / 终章`
- 认不出章节就按 3000 字左右切块，不会一口气把整本书塞进一个视图
- 编码：UTF-8 严格解码，失败自动退回 GBK

**EPUB**

- 走标准 `container.xml → OPF → spine`，按书里的顺序逐章读取
- 目录优先 `toc.ncx`（EPUB2），其次 `nav.xhtml`（EPUB3），都没有就用每页 `<title>`
- 插图从 zip 里就地解码，按屏宽等比缩放后插入正文
- **不解析 CSS**：字体、缩进、复杂版式都不还原，只保留段落、标题、粗斜体、图片、列表

**两种阅读方式，自己选**（设置里切换，全局记住，**默认滚动**）

| 方式 | 交互 |
|---|---|
| **分页** | 一屏一页，**左滑下一页 / 右滑上一页**；翻到章末自动进下一章；底部显示「第 x/y 章 · 第 p/q 页」 |
| **滚动** | 连续下拉，滚到底部自动把下一章接上来，一直加载到全书结束；**不响应左右滑**（两种手势不混用） |

- 点正文显隐上下栏
- 字号 13–30、背景深色 / 米黄 / 浅色、阅读方式都全局记忆
- 切后台或退出会记住「第几章 + 章内位置」，下次直接从那里继续

> **分页怎么算**：用 `StaticLayout` 按真实排版测量（字号、行距、插图都算进去），不是按字数硬切；
> 断点若落在插图中间，整张图挪到下一页。
> **滚动为什么不一次塞整本**：这些轻小说的插图是 1384×2048 的 WebP，按屏宽解码单张约 7 MB，
> 一本 21 张就是 150 MB。所以用**滑动窗口**——同时只保留最近 8 章（TXT）/ 3 章（EPUB），
> 超出后从顶部裁掉，并按裁掉的高度等量回退滚动位置，视觉上不跳。连读十本也不会 OOM。

> **真实数据实测**：拿 7 本实际在看的轻小说（每本 3–5 MB，`OEBPS/Text/*.xhtml` + `OEBPS/Images/*.webp`）
> 跑解析器 —— 书名、21–27 章、每本 13–16 万字全部读出，插图 **165/165 全部命中**，
> 单本打开耗时 6–151 ms。这些书用的是 `../Images/xx.webp` 相对路径 + 自定义属性
> `zy-enlarge-src="self"`，正好验证了路径归一和属性忽略。

### 看图

点文件夹里的 `jpg / jpeg / png / webp / gif / bmp` 直接进全屏看图。顶栏右侧的胶囊可以切换两种浏览方式，选择会记住。

**左右切换（默认）**

- **跟手拖动**：手指按住往左拖，下一张就跟着一起移动；松手按位移和速度决定翻过去还是弹回来，带缓动动画
- 拖过屏宽 28% 或快速甩一下都会翻页；不够就回弹
- 第一张/最后一张继续拖会有**阻尼**（越拖越吃力），松手弹回并提示
- 双指捏合缩放（1–5 倍）、**双击放大/还原**、放大后单指拖动查看细节
- 放大状态下不会误翻页

**上下滑动**

- 整列铺开，一直往下滑就不断出现新图，滚到底显示「已经到底了 · 共 N 张」
- 每张按原始比例算高度；只解码视口前后各一屏内的图，离开视野就置空释放
- 切换模式时保持当前这张，不会跳回开头

其它：`gif` 在 Android 9 以上会动（走 `ImageDecoder`），更老的系统显示第一帧；点一下画面显隐顶栏，显示文件名与「第 3 / 20 张」；大图按屏幕尺寸降采样解码。

### 管理文件夹

首屏右上角 **管理** 进入多选状态：

- 每个文件夹左侧出现小圆圈，点圆圈（或整行）选中，选中后变实心带勾
- 底部固定一条操作栏：**全选 / 取消全选**、**删除(N)**、**完成**
- 删除只是「从应用里移除」，手机上的文件不会被删；会先弹确认框
- **长按任意文件夹可上下拖动排序**，顺序会持久化（下次打开还是这个顺序）
- 底部「完成」或系统返回键都能退出管理状态

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
| `PlayerActivity.java` | 1289 | 播放器本体：MediaPlayer + SurfaceView、手势分发、连播、进度记忆、控制条、外挂字幕/弹幕接线 |
| `MainActivity.java` | 1292 | 片库：目录授权、分层浏览（文件夹 / 视频 / 书籍）、缩略图异步加载 |
| `ImageActivity.java` | 458 | 看图：同目录列表、两种模式切换、预解码、GIF 动图 |
| `PagerLayout.java` | 343 | 左右翻页容器：跟手拖动、松手吸附、边缘阻尼、三槽位复用 |
| `ImageColumn.java` | 241 | 上下滑动看图：整列铺开 + 视口窗口化解码 |
| `ReaderActivity.java` | 1075 | 阅读器：分页（StaticLayout 测量）/ 连续滚动两种模式、字号/背景/进度、EPUB 插图 |
| `Scan.java` | 345 | SAF 目录遍历：视频与书籍列表、同目录文件枚举、文本读取（UTF-8/GBK） |
| `Epub.java` | 310 | EPUB 解析：container → OPF → spine / 目录 / 插图，纯 Java |
| `Sub.java` | 206 | 字幕解析：SRT / VTT / ASS，纯 Java |
| `DanmakuView.java` | 194 | 弹幕渲染：按帧重绘、轨道分配、描边文字 |
| `Danmaku.java` | 171 | 弹幕解析：B站 XML / ASS，纯 Java |
| `Ui.java` | 125 | dp 换算、圆角/圆形背景、时长格式化、标题清洗、自然排序 |
| `Split.java` | 101 | TXT 章节切分（`第X章` / `Chapter N` / 按字数兜底），纯 Java |
| `Nav.java` | 89 | 纯逻辑：把一堆相对路径折叠成某一层的子目录与直属内容 |
| `Store.java` | 81 | SharedPreferences：播放位置、阅读进度、字号/背景/阅读方式、已添加目录 |
| `ZoomView.java` | 192 | 单张图画布：双击/双指缩放、放大后拖动 |
| `Swipe.java` | 58 | 翻页判定算术：位移阈值、甩动速度、边缘阻尼（纯 Java） |
| `Pager.java` | 70 | 分页测量：给定起点算出一页能放多少字（插图不跨页切断） |
| `Flow.java` | 64 | 滚动窗口算术：章节定位、章内比例、裁剪后偏移平移，纯 Java |
| `Entry.java` | 32 | 数据模型：视频或书籍（`kind` + `relPath` + 所属 `tree`） |

**为什么不用 ExoPlayer**：本地播放只需要 H.264/H.265 + AAC，系统 `MediaPlayer` 完全够用。放弃 ExoPlayer 换来的是 APK 从「十几 MB」降到 **82 KB**，启动零依赖加载。

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

## 测试

凡是能离开 Android 的逻辑，都刻意抽成了纯 Java 类，可以直接在 JVM 上跑用例：

```bash
javac -d out/tests \
  app/java/com/kanfan/player/Nav.java \
  app/java/com/kanfan/player/Sub.java \
  app/java/com/kanfan/player/Danmaku.java \
  app/java/com/kanfan/player/Split.java \
  app/java/com/kanfan/player/Epub.java \
  app/java/com/kanfan/player/Flow.java \
  tests/*.java

java -cp out/tests NavTest      # 目录折叠
java -cp out/tests SubTest      # 字幕解析
java -cp out/tests DanmakuTest  # 弹幕解析
java -cp out/tests SplitTest    # TXT 章节切分
java -cp out/tests EpubTest     # EPUB 解析（测试里现场打包一个 epub）
java -cp out/tests FlowTest     # 滚动窗口的偏移与裁剪
java -cp out/tests SwipeTest    # 翻页判定：阈值、甩动、边缘阻尼
```

**180 项用例**，目前全部通过：

| 用例 | 数量 | 重点覆盖 |
|---|---:|---|
| `NavTest` | 32 | 多层嵌套、空输入、**前缀陷阱**（`AB` 不属于 `A`）、视频/书籍/图片分开计数 |
| `SubTest` | 34 | SRT/VTT/ASS 三种时间轴（含 ASS 百分秒）、`\N` 与标签剥离、乱序 `Format`、时间重叠取新句、区间边界 |
| `DanmakuTest` | 22 | B站 XML 的三种模式与十进制颜色、HTML 实体、ASS 的 `\c&HBBGGRR&`、排序与坏行跳过 |
| `SplitTest` | 22 | 中英文标题、首章前的序言、正文行不被误判为章节、无章节时按长度切块 |
| `EpubTest` | 20 | container/OPF/spine、NCX 与 nav 两种目录、`../` 路径归一、图片字节、script/style 剔除 |
| `FlowTest` | 24 | 章节定位的边界、章内比例、裁剪后偏移平移、无效裁剪 |
| `SwipeTest` | 26 | 翻页阈值、甩动速度方向、首尾边界、阻尼系数、吸附目标与动画时长 |

> UI 与播放链路仍没有自动化测试 —— 这台开发机上没有安卓设备也没有模拟器，只能装机手测。
> 分页测量依赖 `StaticLayout`，属 Android 运行时，未纳入 JVM 用例。

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
- ASS 字幕只取「时间 + 文本」，复杂特效（字体定位、卡拉OK、绘图）会退化成普通居中文本
- 弹幕不支持代码弹幕 / BAS 等高级类型；ASS 弹幕统一按滚动处理，不还原位置与动画
- 阅读器**不解析 EPUB 的 CSS**：字体、缩进、图文混排等版式不还原，只保留段落 / 标题 / 粗斜体 / 图片
- 看图不支持旋转与「保存为」，GIF 在 Android 8 及以下只能看第一帧
- 阅读器没有书签与标注
- 倍速在退出播放器后回到 1.0×，不做跨会话记忆
- 没有接入 MediaSession，锁屏与通知栏无控制，也没有画中画
- 只在个人的几台设备上手动验证过，UI 与播放链路没有自动化测试（解析逻辑有）

---

## 许可

[MIT](LICENSE)
