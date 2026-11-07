<div align="center">

<img src="docs/icon.png" width="110" alt="看番">

# 看番 · KanFan

**一个 714 KB 的安卓本地媒体浏览器：视频 + 字幕弹幕 + 电子书 + 图片 + 解压**

纯 Java + 系统原生 API · 零第三方依赖 · 无广告 · 无会员 · 不联网

`Min SDK 23 (Android 6.0)` · `Target SDK 34` · `APK 714 KB`（含 arm64 + armv7a 原生库）

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
| **解压** | 点文件夹里的 `.zip` / `.7z` / `.rar` 直接查看内容并解压：支持 ZipCrypto 与 WinZip AES 加密、rar5、7z（LZMA/LZMA2），以及 **rar / zip / 7z 三种分卷**；需要密码时弹窗输入 |
| **游戏** | 进到移植版所在文件夹，顶部会出现「启动游戏」卡：**TyranoBuilder 可运行**（内置本地 HTTP 服务 + WebView，不拷游戏）；ONS / KRKR 能识别但暂不支持运行 |
| **下载** | 顶部「下载」→ 贴 magnet 或选 `.torrent`：内置 BT 引擎（jlibtorrent），支持 DHT/加密/uTP/磁力元数据；后台前台都在下，通知栏显示速度；下完自动停种，可在下载页直接播放 |
| **压缩** | 管理页勾选文件 → 压缩成 ZIP：流式写入、Zip64、已是压缩格式的自动改存储；有进度、可取消，失败不留半截文件 |
| **重命名** | 长按任意文件 → 重命名，可改扩展名（`x.mp4` → `x.zip` 直接当压缩包打开）；也可「用其他应用打开」交给系统处理 |
| **管理 / 隐藏** | 长按文件夹或点右上角「管理」进多选：首屏移除/拖动排序文件夹，目录内隐藏文件；首屏底部可一键恢复；长按文件可彻底删除 |
| **阅读** | 点文件夹里的 `.txt` / `.epub` 直接进阅读器；**分页 / 滚动两种模式自选**（分页左滑右滑翻页，滚动连续加载）；章节识别、目录跳转、字号与三种背景、进度记忆；EPUB 的插图随文显示；**`.pdf` 用系统 PdfRenderer 连续滚动阅读，可缩放、跳页、记忆页码** |
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

### PDF

点文件夹里的 `.pdf` 直接进阅读器——用系统自带的 `PdfRenderer` 渲染，不引第三方库：

- **连续纵向滚动**，页面按屏宽等比铺满，一页接一页
- 双指缩放（1×–5×）、双击 2.5×、放大后拖动、松手惯性滑动
- 点画面显隐顶栏；顶栏有页码与「跳页」（输入页号直接跳）
- 记住上次读到第几页，下次打开接着看
- 渲染在后台线程串行做（`PdfRenderer` 不是线程安全的），位图进 LruCache，滑过去的页自动回收
- 有些提供方给的是管道而不是真文件，`PdfRenderer` 读不了——会自动复制一份到缓存再打开

> 只做「看」：PDF 里的文字不可选、不可搜、不能复制。要这些能力得引入 PDFium/MuPDF 这类库，
> 与「零第三方依赖」冲突。加密的 PDF 也打不开（会提示）。

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

### 管理 / 隐藏

右上角 **管理** 进入多选状态，**首屏和目录里都能用**：

| 在哪 | 能做什么 |
|---|---|
| 首屏（文件夹列表） | 勾选后 **删除**（= 从应用里移除这个文件夹）、**长按拖动排序** |
| 任意目录内 | 勾选文件后 **隐藏**（不再显示，磁盘上还在） |

- **长按一个文件夹**也能直接进管理页，并顺手把它选中（不用先点右上角「管理」）
- 每行左侧出现小圆圈，点圆圈或整行都算选中，选中后变实心带勾
- 底部固定操作栏：**全选 / 取消全选**、**隐藏(N) 或 删除(N)**、**完成**
- 管理页里长按单个文件/子文件夹仍然是「重命名 / 用其他应用打开 / 隐藏 / 彻底删除」菜单；
  首屏长按文件夹是拖动排序
- 删除只是「从应用里移除」，手机上的文件不会被删；会先弹确认框
- 「完成」或系统返回键都能退出管理状态

**隐藏是按 document id 记的**，所以文件改名后仍然是隐藏状态，不会因为改了名又冒出来。
回到首屏，列表底部会出现「已隐藏 N 个文件 · 点此恢复」，点一下全部恢复。

想真正删掉文件，长按单个文件 → **彻底删除文件**（红色确认框，会从手机存储里真删）。

### 重命名 / 用其他应用打开

长按片库里的任意文件：

| 操作 | 说明 |
|---|---|
| **重命名** | 弹窗里只选中主文件名，扩展名不会被误删；改完立刻重新扫描，分类跟着变 |
| **用其他应用打开** | 把文件交给系统里能处理它的 App（装 APK、看 PDF 等） |

改扩展名会**改变文件在片库里的归类**，这也是最实用的两招：

- `xxx.mp4`（其实是压缩包）→ 改成 `xxx.zip` → 出现在「压缩包」，点开就能解压
- `xxx.bin` / `xxx.zip`（其实是安装包）→ 改成 `xxx.apk` → 出现在「其他文件」，点它就会调起安装

归类只看**扩展名**，不看系统给的 MIME —— 因为有些文件提供方在改名后仍返回旧 MIME，
按 MIME 判会把改名后的包当成视频，打不开。

### 解压

片库里点 `.zip`（或 `.7z` / `.rar`）进入压缩包页面：

- 先列出里面的文件和目录（名字 + 解压后大小），能直接看出是不是你要的那包
- 点 **解压** → 选「解压到新建文件夹「包名」」或「解压到当前文件夹」
- 加密的包会先弹窗要密码，**只读文件头校验，错了当场提示**，不用等解压完才发现
- 解压过程中显示进度与当前文件名，可随时取消

支持范围：

| 格式 | 说明 |
|---|---|
| `zip`（存储 / deflate，含 ZIP64） | 完整支持，纯 Java |
| `zip` + 传统加密（ZipCrypto） | 支持，WinRAR / 7-Zip 默认的「ZIP 加密」就是这种 |
| `zip` + WinZip AES（AE-1 / AE-2，128/192/256） | 支持，会校验 HMAC 与 CRC |
| `rar` / `rar5`（含加密） | 支持，走原生 unrar，**边解边写盘** |
| `7z`（LZMA / LZMA2 / BCJ / Delta） | 支持，走 7-Zip 的 C 解码器；**加密的 7z 不支持** |

压缩包里如果所有文件都在同一个顶层目录下，会**自动剥掉这一层**，避免解出「番名/番名/01.mkv」。

#### 分卷

`name.part1.rar`、`name.z01…+name.zip`、`name.7z.001` 这些都能直接点开，
**从第几卷点进去都一样**——会先归一到整组再读：

| 命名 | 读取方式 |
|---|---|
| 分卷 zip（`.z01…` / `.zip.001`） | 各卷按序拼成一个逻辑文件，偏移本来就是相对整包的 |
| 7z 切卷（`.7z.001`） | 原生层把多段 fd 虚拟拼接，跨卷读 |
| rar 分卷（`.partN.rar` / `.rNN`） | 各卷按真实文件名软链到缓存目录，unrar 才能按名续卷 |

列表里只保留主卷一行；少了一卷会提示「缺第 3 卷」。

### 压缩

管理页勾选文件 → **压缩** → 起个名字（默认 `<文件夹名>.zip`）→ 开始。

- 流式写：边读边写，内存占用与文件大小无关
- **Zip64**：单个文件或整包超过 4 GB 也能写
- 视频 / 图片 / 音频 / 压缩包这类已经压过的内容自动改用**存储**，不浪费 CPU
- 可取消；失败或取消会把没写完的文件删掉

格式固定为 ZIP：RAR 的压缩算法是专有授权（开源实现只能解压），7z 要另写编码器，
而这两者对视频的压缩收益都接近 0。

**关于原生库**：7z 与 rar 不是 Java 实现的，而是用 NDK 把
[7-Zip](https://www.7-zip.org/) 的 C 解码器与 [unrar](https://www.rarlab.com/) 编译成
`lib/arm64-v8a/libkanfan.so` 与 `lib/armeabi-v7a/libkanfan.so`（约 760 KB / 586 KB）。

- **rar**：走 unrar 的 DLL 接口，**逐个文件解到缓存再搬进目标目录**，不会一次性占两份空间
- **7z**：走 7-Zip 的 `7zDec.c`，它把整个「固态块」解到内存——**单个文件超过内存就会失败**，
  同时**不支持加密的 7z**（C 解码器没有 AES 层）
- 原生库没编进去也能用，只是 7z/rar 会提示「原生库没能加载」，其余功能不受影响

### Galgame 移植版

进到移植版所在的文件夹，如果目录结构对得上，列表顶部会多一张卡：

| 引擎 | 识别特征 | 状态 |
|---|---|---|
| **TyranoBuilder**（HTML5） | `index.html` + `tyrano/` 或 `data/` | ✅ 可以直接启动 |

| **ONS / NScripter** | `*.nsa` / `*.ns2` / `*.sar` / `nscript.dat` / `0.txt` | ✅ 可以直接启动（仅 arm64） |
| **KiriKiri / TVP** | 任意 `*.xp3` | ✅ 可启动（**首次要导入**，见下） |

Tyrano 的做法：起一个只服务这个文件夹的**本地 HTTP 服务**（127.0.0.1），WebView 打开
`http://127.0.0.1:<端口>/index.html`。

- 不用把几百 MB 的游戏拷进应用目录，直接从 SAF 流式读
- 端口按游戏路径算，固定下来——WebView 的存档（localStorage）是按 origin 隔离的，端口一变就“丢档”
- 支持 Range 请求，BGM/视频可以拖动
- 左上角有个半透明 `×`，点它确认退出；返回键先退网页历史，再退游戏

**ONS 怎么跑的**：内置 [onscripter-engine-android](https://github.com/matthewn4444/onscripter-engine-android)
（ONScripter 的 Android fork，GPL-2.0-or-later，支持中日英韩与 UTF-8），用 NDK 编成
`libonscripter.so`（9.8 MB）+ `libsdl.so`（2.8 MB）：

- 引擎直接拿 `content://` 读 SAF 目录，**游戏不用拷进应用**
- 存档写回同一个游戏目录，所以那个文件夹需要**写权限**（添加文件夹时已申请）
- 引擎跑在 `GLSurfaceView` 里，自带触屏映射；左上角 `×` 退出，返回键会先确认
- 只编了 **arm64**：32 位设备会提示不支持

**KRKR 怎么跑的**：内置 [krkrsdl2](https://github.com/uyjulian/krkrsdl2)（吉里吉里Z 的 SDL2 移植，MIT；
内核 krkrz 是 BSD 系），CMake + NDK 编成 `libmain.so`（7.7 MB）+ `libSDL2.so`（1.5 MB）：

- 引擎只认**真实文件路径**（要 open/seek/写存档），SAF 的 `content://` 接不了
- 所以第一次启动会提示「导入」：把游戏目录**复制一份**到应用目录（`Android/data/.../files/games/`），
  有进度、可取消；导入一次之后直接进游戏，原目录不动
- 复制量等于游戏大小（几百 MB 到几 GB），会占双份空间——这是 KRKR 的硬约束，没办法绕
- 存档写在导入目录里（`savedata/`），删掉导入目录等于删档

**插件预检**：Kirikiroid2 的汉化移植版常在 `startup.tjs` 里 `Plugins.link("xxx.dll")`。
krkrsdl2 只内置 7 个（`extrans / wutcwf / wuvorbis / fstat / json / varfile / krglhwebp`），
其余（`krmovie`、`menu`、`windowEx`、`layerExSave`、`saveStruct`、`csvParser`…）在 Android 上根本没有。
启动前会扫一遍导入目录里的 `.tjs / .ks / .csv / .txt`，把「缺哪些、各自会导致什么」列出来，
再让你决定「仍然启动 / 取消」——不至于对着一片黑屏猜。

### 下载（磁力 / BT）

顶部「下载」进下载页：贴 `magnet:` 链接点「添加」，或者选一个本地 `.torrent` 文件。

- **引擎**：`jlibtorrent` 1.2.0.18（libtorrent 1.1 的 Java/JNI 绑定，GPLv3），
  协议、DHT、MSE 加密、uTP 都在原生 `.so` 里，Java 这层只负责加任务和读状态
- **后台不断**：下载由前台服务持有，切后台、锁屏都继续；通知栏显示「N 个任务 · x MB/s」
- **做种可选**：默认下完就停（不占上行）；下载页勾「下完继续做种」才会继续上传，
  列表里会显示 `做种中 · ↑ x/s`，通知栏也会写「做种 N 个」
- **保存位置**：`/storage/emulated/0/Android/data/com.kanfan.player/files/downloads`
  （应用私有外部目录，卸载即删、不进相册）。多文件种子会在里面建一个以种子名命名的子文件夹；
  下载页那行路径点一下可以复制，方便去文件管理器里找
- **进度与任务**：任务列表（磁力/名字/暂停状态）存在 SharedPreferences，重启后自动续上，
  已下好的分片会被重新校验，不会重下

> 支持 magnet 与本地 `.torrent`；http(s) 的种子直链暂未做（会提示）。不内置 tracker 列表。

### 隐私

| 权限 | 什么时候用 |
|---|---|
| `WAKE_LOCK` | 播放时屏幕常亮 |
| `INTERNET` / `ACCESS_NETWORK_STATE` | **只有点「添加」开始下载时才用**；不下载就不发任何网络请求 |
| `FOREGROUND_SERVICE` / `FOREGROUND_SERVICE_DATA_SYNC` | 后台下载时挂前台通知，Android 8+ 否则会被掐 |
| `POST_NOTIFICATIONS` | 显示下载进度通知（Android 13+ 会问一次） |

没有统计、没有广告、没有崩溃上报，APK 里也没有任何第三方网络 SDK——唯一的联网代码是 BT 引擎本身。
明文 http 只对 `127.0.0.1` 放开（运行 Tyrano 游戏的本地服务），其余域名一律要求 https。

看视频通过系统的 **Storage Access Framework** 访问：只读你亲手选中的那个目录，连相册（MediaStore）都读不到。
下载写在应用自己的外部目录 `Android/data/com.kanfan.player/files/downloads`，卸载即删，不会污染你的相册。

---

## 架构

除 BT 引擎外零第三方库（没有 AndroidX、没有 ExoPlayer、没有 Glide），其余全部用系统 API 拼出来：

| 文件 | 行数 | 职责 |
|---|---:|---|
| `PlayerActivity.java` | 1289 | 播放器本体：MediaPlayer + SurfaceView、手势分发、连播、进度记忆、控制条、外挂字幕/弹幕接线 |
| `MainActivity.java` | 2116 | 片库：目录授权、分层浏览、缩略图、管理页（隐藏/拖动排序/压缩）、长按重命名 |
| `ArchiveActivity.java` | 1142 | 压缩包：条目列表、密码弹窗、目标选择、进度与取消、分卷归一 |
| `ImageActivity.java` | 458 | 看图：同目录列表、两种模式切换、预解码、GIF 动图 |
| `PagerLayout.java` | 343 | 左右翻页容器：跟手拖动、松手吸附、边缘阻尼、三槽位复用 |
| `ImageColumn.java` | 241 | 上下滑动看图：整列铺开 + 视口窗口化解码 |
| `ReaderActivity.java` | 1075 | 阅读器：分页（StaticLayout 测量）/ 连续滚动两种模式、字号/背景/进度、EPUB 插图 |
| `Scan.java` | 430 | SAF 目录遍历：分层列表、同目录文件枚举、文本读取（UTF-8/GBK） |
| `Epub.java` | 310 | EPUB 解析：container → OPF → spine / 目录 / 插图，纯 Java |
| `Sub.java` | 206 | 字幕解析：SRT / VTT / ASS，纯 Java |
| `DanmakuView.java` | 194 | 弹幕渲染：按帧重绘、轨道分配、描边文字 |
| `Danmaku.java` | 171 | 弹幕解析：B站 XML / ASS，纯 Java |
| `Ui.java` | 125 | dp 换算、圆角/圆形背景、时长格式化、标题清洗、自然排序 |
| `Split.java` | 101 | TXT 章节切分（`第X章` / `Chapter N` / 按字数兜底），纯 Java |
| `Nav.java` | 89 | 纯逻辑：把一堆相对路径折叠成某一层的子目录与直属内容 |
| `Store.java` | 81 | SharedPreferences：播放位置、阅读进度、字号/背景/阅读方式、已添加目录 |
| `ZoomView.java` | 192 | 单张图画布：双击/双指缩放、放大后拖动 |
| `Zip.java` | 717 | zip 读取：中央目录 / ZIP64 / deflate / ZipCrypto / WinZip AES，可跨分卷（纯 Java） |
| `KrkrActivity.java` | 30 | KiriKiri 运行器：继承 SDLActivity，把导入目录传给 native |
| `KrkrCompat.java` | 190 | KRKR 插件预检：解析 Plugins.link、内置/缺失分类与后果说明 |
| `GameImport.java` | 130 | SAF → 应用目录的递归复制（带进度/取消），KRKR 用 |
| `org/libsdl/app/*` | 4300 | SDL2 官方 Android 胶水层（原样引入） |
| `OnsActivity.java` | 250 | ONS 运行器：托管 ONScripterView、GL 全屏、错误与退出 |
| `com/onscripter/*` | 2400 | ONScripter 引擎的 Java 层（原样引入，GPL-2.0-or-later） |
| `TyranoActivity.java` | 250 | Tyrano 运行器：WebView + 全屏 + 退出确认 + 端口选择 |
| `GameServer.java` | 308 | 只服务一个 SAF 目录的 HTTP 服务：Range、MIME、路径穿越防护、并发缓存 |
| `GalGame.java` | 120 | 移植版引擎识别（Tyrano/ONS/KRKR），纯 Java |
| `TorrentActivity.java` | 587 | 下载页：magnet 输入、选种子、任务列表与操作、下载文件播放 |
| `TorrentEngine.java` | 500 | BT 引擎外壳：任务增删改查、状态轮询、持久化、文件枚举 |
| `TorrentService.java` | 166 | 下载前台服务：持有引擎、每秒刷新、通知栏速度 |
| `PdfActivity.java` | 449 | PDF 阅读：PdfRenderer 生命周期、后台渲染、跳页、页码记忆、非 seekable 落盘兜底 |
| `PdfView.java` | 369 | PDF 画布：连续滚动、双指/双击缩放、惯性、按需渲染 + LruCache |
| `PdfLayout.java` | 108 | PDF 排版算术：页偏移、可视范围、缩放后平移夹取（纯 Java） |
| `ZipWrite.java` | 371 | zip 写入：流式、Zip64、UTF-8 名、进度与取消（纯 Java） |
| `Vols.java` | 228 | 分卷命名识别：`partN.rar` / `.rNN` / `.zNN` / `.001`，纯 Java |
| `Nat.java` | 47 | 原生解压的 Java 绑定（rar / 7z，支持多卷拼接） |
| `Saf.java` | 118 | SAF 写入：建目录、建文件、MIME 推断 |
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

### 第三方二进制

`libs/` 下是从 Maven Central 取的 jlibtorrent 1.2.0.18：

```
libs/jlibtorrent.jar                              # 527 个 Java 类（JNI 绑定）
libs/jni/arm64-v8a/libjlibtorrent-1.2.0.18.so     # 6.6 MB
libs/jni/armeabi-v7a/libjlibtorrent-1.2.0.18.so   # 6.2 MB
```

`libs/ons/` 是 ONS 引擎（ONScripter + SDL2）的编译产物，构建步骤与补丁见 `libs/ons/BUILD.md`：
`libonscripter.so`（9.8 MB）+ `libsdl.so`（2.8 MB），只有 arm64。

`libs/krkr/` 是 KiriKiri（krkrsdl2，内核 krkrz）的编译产物：`libmain.so`（7.7 MB）+ `libSDL2.so`（1.5 MB），
构建方式见 `libs/krkr/BUILD.md`，同样只有 arm64。

构建脚本会把 jar 一起丢给 `d8`，并把所有 `.so` 塞进 APK 的 `lib/<abi>/`。
原生库必须保留带版本号的文件名——jlibtorrent 先按 `libjlibtorrent-<ver>` 找，找不到才退回 `libjlibtorrent`。

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
  app/java/com/kanfan/player/Swipe.java \
  app/java/com/kanfan/player/Vols.java \
  app/java/com/kanfan/player/Zip.java \
  app/java/com/kanfan/player/ZipWrite.java \
  app/java/com/kanfan/player/PdfLayout.java \
  app/java/com/kanfan/player/GalGame.java \
  app/java/com/kanfan/player/KrkrCompat.java \
  tests/*.java

java -cp out/tests NavTest      # 目录折叠
java -cp out/tests SubTest      # 字幕解析
java -cp out/tests DanmakuTest  # 弹幕解析
java -cp out/tests SplitTest    # TXT 章节切分
java -cp out/tests EpubTest     # EPUB 解析（测试里现场打包一个 epub）
java -cp out/tests FlowTest     # 滚动窗口的偏移与裁剪
java -cp out/tests SwipeTest    # 翻页判定：阈值、甩动、边缘阻尼
java -cp out/tests ZipTest D:/tmp/ziptest   # 真实 zip：普通/存储/ZipCrypto/AES256/deflate+加密
java -cp out/tests VolsTest     # 分卷命名：partN / rNN / zNN / 001，排序与缺卷
java -cp out/tests ZipVolTest   # 分卷 zip：现场切卷，跨卷读、缺卷报错
java -cp out/tests ZipWriteTest # 压缩：存储/deflate/Zip64/UTF-8 名/取消，产物交给 7z t
java -cp out/tests PdfLayoutTest # PDF 排版：页偏移、可视范围、缩放焦点、平移夹取
java -cp out/tests GalGameTest    # 移植版引擎识别：Tyrano / ONS / KRKR
java -cp out/tests KrkrCompatTest # KRKR 插件预检：Plugins.link 解析与缺失分类
```

**396 项用例**，目前全部通过：

| 用例 | 数量 | 重点覆盖 |
|---|---:|---|
| `NavTest` | 40 | 多层嵌套、空输入、**前缀陷阱**（`AB` 不属于 `A`）、视频/书籍/图片/压缩包/其他分开计数 |
| `SubTest` | 34 | SRT/VTT/ASS 三种时间轴（含 ASS 百分秒）、`\N` 与标签剥离、乱序 `Format`、时间重叠取新句、区间边界 |
| `DanmakuTest` | 22 | B站 XML 的三种模式与十进制颜色、HTML 实体、ASS 的 `\c&HBBGGRR&`、排序与坏行跳过 |
| `SplitTest` | 22 | 中英文标题、首章前的序言、正文行不被误判为章节、无章节时按长度切块 |
| `EpubTest` | 20 | container/OPF/spine、NCX 与 nav 两种目录、`../` 路径归一、图片字节、script/style 剔除 |
| `FlowTest` | 24 | 章节定位的边界、章内比例、裁剪后偏移平移、无效裁剪 |
| `SwipeTest` | 26 | 翻页阈值、甩动速度方向、首尾边界、阻尼系数、吸附目标与动画时长 |
| `ZipTest` | 57 | 用 7-Zip 现场生成的 5 种 zip 逐字节比对；错密码必须被拒；坏文件必须抛错 |
| `VolsTest` | 33 | 新式/旧式 rar、`.z01` 分卷、7-Zip 切卷的归组与排序；`part10` 按数值排；缺卷提示 |
| `ZipVolTest` | 8 | 把完整 zip 切成 2/3 卷（切点故意不对齐）后跨卷读；少一卷必须报错 |
| `ZipWriteTest` | 26 | 写出来的包交给 `7z t` 校验；deflate/store、Zip64、未知大小、两万条目、取消 |
| `KrkrCompatTest` | 22 | `Plugins.link` 各种写法、路径归一、内置/缺失分类、目录扫描与忽略非脚本 |
| `GalGameTest` | 21 | Tyrano 目录结构、ONS 各文件名、KRKR 的 xp3、大小写、裸 index.html 不误判 |
| `PdfLayoutTest` | 41 | 页偏移与总高、可视范围二分、缩放焦点保持、平移夹取、空/坏页不崩 |

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
- **压缩只能存成 ZIP**：rar 的压缩算法是专有授权，7z 需要另写编码器（目前只有解码器）
- 分卷只认标准命名（`partN.rar` / `.rNN` / `.zNN` / `.###`）；被改过名的分卷认不出来
- 极少数不让建软链的存储上，rar 分卷会退回「先复制副本再解」，会多占一份空间
- 加密的 7z、以及单个文件超过可用内存的 7z 固态块，解不了
- 重命名只支持文件，暂不支持文件夹；改成不认识的后缀后，文件会从列表里消失（磁盘上还在）
- 解压需要目录的**写入**权限：v2.2 之前添加的文件夹只有读权限，需要移除后重新添加一次
- 看图不支持旋转与「保存为」，GIF 在 Android 8 及以下只能看第一帧
- 阅读器没有书签与标注
- Galgame：Tyrano、ONS、KRKR 都可运行（后两者仅 arm64）
- KRKR 首次启动要复制整个游戏目录，占双份空间；复制中途退出会清掉半成品
- KRKR 插件只能预检、不能补齐：缺 `krmovie`（OP 视频）、`menu`、`layerExSave` 等的游戏仍可能黑屏
- 预检只扫未打包的脚本；脚本全在 `data.xp3` 里的游戏扫不到，会直接启动
- ONS 存档写在游戏目录里，换一个文件夹存档就没了；游戏目录只读时存档会失败
- Tyrano 只认根目录的 `index.html`；入口在子目录里的自制导出需要手动改结构
- 下载只支持 magnet 与本地 `.torrent`；不支持 http(s) 种子直链
- 做种默认关闭；打开后只有 App 进程活着时才上传（杀掉应用即停）
- BT 引擎是 2018 年的 jlibtorrent 1.2.0.18（libtorrent 1.1.x，GPLv3）；32 位与 64 位都带了 `.so`，
  但原生库按 4KB 页对齐，Android 15 的 16KB 页机型可能加载失败
- PDF 只能看：文字不可选/不可搜/不能复制；加密 PDF 打不开；超大页面按 1800px 渲染，放到 3× 以上会略糊
- 倍速在退出播放器后回到 1.0×，不做跨会话记忆
- 没有接入 MediaSession，锁屏与通知栏无控制，也没有画中画
- 只在个人的几台设备上手动验证过，UI 与播放链路没有自动化测试（解析逻辑有）

---

## 许可

本仓库自己的代码是 [MIT](LICENSE)。

但 APK 里打包了两个 GPL 组件：

- **GPLv3**：[jlibtorrent](https://github.com/frostwire/jlibtorrent)（libtorrent 的 Java 绑定）
- **GPL-2.0-or-later**：[ONScripter](https://github.com/matthewn4444/onscripter-engine-android)（ONS 引擎）
- **MIT + BSD 系**：[krkrsdl2](https://github.com/uyjulian/krkrsdl2) 与 [krkrz](https://github.com/krkrz/krkrz)（KRKR 引擎）

两者兼容，**分发 APK 时整体按 GPLv3 走**。源码本来就公开，满足它们的要求。
