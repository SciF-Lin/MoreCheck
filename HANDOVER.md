# MoreCheck 接手指南

> 面向接手这个项目的下一个人（或 AI）。读完这一份就能开工：它是什么、和 CheckInMate 什么关系、代码在哪、怎么构建、怎么发版。
> 还没做完的事见 [`docs/待办与已知问题.md`](docs/待办与已知问题.md)，历代版本见 [`docs/版本历史.md`](docs/版本历史.md)。

## 0. 文档地图

| 你的目的 | 读哪份 |
| --- | --- |
| **接手干活 / 改代码 / 发版** | 本文（读完即可） |
| 怎么构建、签名怎么配 | [`BUILD.md`](BUILD.md)（本文第 6 节是浓缩版） |
| 还没做什么、哪里有坑、已知 bug | [`docs/待办与已知问题.md`](docs/待办与已知问题.md) |
| 某个版本改了什么、版本号怎么来的 | [`docs/版本历史.md`](docs/版本历史.md) |
| 这个 App 有什么功能、怎么装 | [`README.md`](README.md)（面向使用者） |
| 图标设计源文件 | [`design/icon-concepts/`](design/icon-concepts/)（`showcase.html` 可预览） |

## 1. 这是什么

`E:\download\app\MoreCheck` 是一个 **Android 打卡应用**的公开工作区：Kotlin + Jetpack Compose，本地优先（不登录也能用），登录后可云同步。应用名 **MoreCheck**，包名 `com.morecheck`。

目录里同时放着：五个可构建的源码快照（`sources/v1.1.2`、`v1.1.50`、`v1.1.70`、`v1.1.90`、`v1.4.0`）、对应已签名安装包（`dist/`）、图标与二维码设计源（`design/`）、构建与发布文档。

**这不是一个"一版代码持续迭代"的仓库**，而是**按上游版本逐版快照**：每个 `sources/vX.Y.Z/MoreCheck/` 都是独立可构建的完整 Gradle 工程，改哪个版本就进哪个目录，互不影响。

## 2. 与 CheckInMate 的关系（先看这一节）

- **MoreCheck** 基于 **CheckInMate** 的源码改造而来，是对外公开的发行版。
- **CheckInMate** 是开发者自己用的个人应用，**不对外公开**，功能更全、迭代更快（正式版已到 1.4.0，其后还有 1.4.x / 2.0 的 beta）。
- 两者**功能相似但是两个独立产品**，绝不能混用身份：

| 项 | MoreCheck（本项目） | CheckInMate（上游，私有） |
| --- | --- | --- |
| 定位 | 对外公开的打卡应用 | 开发者自用，不公开 |
| 仓库 | <https://github.com/SciF-Lin/MoreCheck>（Public） | <https://github.com/SciF-Lin/CheckInMate>（Private，匿名访问 404） |
| 包名 | `com.morecheck` | `com.checkinmate` |
| 签名 DN | `CN=MoreCheck, OU=MoreCheck, O=MoreCheck, L=Beijing, ST=Beijing, C=CN` | `CN=CheckInMate, OU=Personal, O=CheckInMate, L=Local, ST=Local, C=CN` |
| 证书 SHA-256 | `d3da0ec0b0fc3a91de57ba783af5e9a4dcf25f5fdcb403941a77be461c00d996` | `8fca12137940e4dd9e97d11a6366c03c6b1f7c713ecaacc35f79667f9e955e15` |
| 云端 | `https://morecheck.app.workbuddy.host`（自有云环境） | 上游自己的云环境 |
| 版本号 | 沿用上游 tag：1.1.2 / 1.1.50 / 1.1.70 / 1.1.90 / 1.4.0 | 1.1.x → 1.4.0 |

> 本文会随公开仓库一起发布。上游是私有仓库，外部点上面的 CheckInMate 链接会 404；如果不希望公开这层关系，推送前删掉表中那一行即可，不影响构建与发布。

**三条红线：**

1. **签名不能换。** 已发布的五个包共用 `keystore/morecheck.jks`。换密钥 = 老用户无法覆盖升级，只能卸载重装（数据全丢）。
2. **包名不能改成 `com.checkinmate`，也不能让两者互相覆盖安装。** 包名与签名任意一项不同就装不上去，这是有意的隔离。
3. **上游的私有资产不进这个公开仓库**：`checkinmate.jks`、上游的 `docs/` 与 `HANDOVER.md`、内部评审/历史文档、`dist/` 交付说明。`sources/v1.2.0` 仍是上游原样快照（含 `.git` 和密钥），已加入 `.gitignore`，**只在本机参考**；`sources/v1.4.0/MoreCheck` 已完成品牌改造并公开，其上游附带文档/脚本/快照目录仍不入库。

> 版本号为什么从 1.1.x 开始、而不是 1.0.0：沿用上游的版本序列与 `versionCode`（4 / 10 / 26 / 39），这样两边的更新日志、issue 描述能对得上。发新版时在上游 tag 基础上继续，例如上游 1.2.0 → MoreCheck 1.2.0。

## 3. 当前状态（2026-11-24）

| 项 | 值 |
| --- | --- |
| 最新版本 | **1.4.0**（`versionCode 69`，功能最完整，README 标"推荐"） |
| 同仓其他版本 | 1.1.90（39）、1.1.70（26）、1.1.50（10）、1.1.2（4） |
| 包名 / 最低系统 | `com.morecheck` / Android 10（API 29），targetSdk 36 |
| 安装包 | `dist/MoreCheck-<版本>.apk`，五个包同签名，可互相覆盖升级 |
| 云环境 | `https://morecheck.app.workbuddy.host`（应用 id `wbapp_2KA020VX3CkCXSDkX332mn`） |
| 检查更新 | 跳转 <https://github.com/SciF-Lin/MoreCheck/releases>（页面已有内容） |
| GitHub Release | **五版均已发布**（1.1.x 四版于 2026-11-22，`v1.4.0` 于 2026-11-24），`v1.4.0` 标为 Latest，均带 APK 资产；发版走 `gh release`，见 7.2 |
| 云端发布索引 | 已建 `app_releases` 表，但客户端还没读，见待办 P0-2 |

`dist/` 里是**工作区当前包**，GitHub Release 上是**已发布资产**。两者在 1.1.2 / 1.1.70 / 1.1.90 上不一致，原因见下方说明，别当成抄错：

| 版本 | `dist/` 当前文件 | GitHub Release 资产（已发布） |
| --- | --- | --- |
| 1.4.0 | 12.00 MB · `4ebd4e13ca4098b82567fe40e0bbaafeda44fc30af4d5b3448ba7d698b1abbfd` | 12.00 MB · `4ebd4e13ca4098b82567fe40e0bbaafeda44fc30af4d5b3448ba7d698b1abbfd` |
| 1.1.90 | 7.20 MB · `a57beaa086ba58bdd173bed9916d778ff424c9001c8c986091a0442d2ed543a9` | 7.19 MB · `a57beaa086ba58bdd173bed9916d778ff424c9001c8c986091a0442d2ed543a9` |
| 1.1.70 | 6.47 MB · `3f89aadc36c59e6a0b857abec366d56f3497a02fbadce108d167345ebc2486e2` | 6.45 MB · `3f89aadc36c59e6a0b857abec366d56f3497a02fbadce108d167345ebc2486e2` |
| 1.1.50 | 3.15 MB · `8fcedac5f11a063a81eb17f6456c9929707a6f498eda22b66f561747c1116a08` | 3.15 MB · `8fcedac5f11a063a81eb17f6456c9929707a6f498eda22b66f561747c1116a08` |
| 1.1.2 | 3.04 MB · `5a6202d18077886aa54174230a0e908fc6af664b054fb7b1497aa2e0ece665c3` | 3.02 MB · `5a6202d18077886aa54174230a0e908fc6af664b054fb7b1497aa2e0ece665c3` |

> ⚠️ **分叉成因（已知，暂时保留）**：`drawable-nodpi/ic_mimo_logo.png`（应用内 logo，不是桌面图标）于 2026-11-22 13:14 在 1.1.2 / 1.1.70 / 1.1.90 三个版本里被换成方案 A 的蓝青对勾，但**改动没有提交**，13:20 用新图重建了这三个包。
> 两包逐条目比对，差异**只有** `res/nH.png` 一个资源（7735 → 24018 字节），其余条目大小全等，代码与桌面启动图标完全相同（`aapt2 dump badging` 两边都指 `res/BW.xml` 自适应图标）。1.1.50 未重建，因此两边一致。
> 结论：**GitHub 上的是"旧 logo"包，`dist/` 里是"新 logo"包**。要消除分叉，就提交 logo 改动，再用 `gh release upload <tag> <apk> --clobber` 覆盖三个资产，并同步本表。

## 4. 目录地图

```
E:\download\app\MoreCheck\
├── README.md                  # 使用者向：功能、下载、安装、FAQ
├── BUILD.md                   # 构建与自备签名说明
├── HANDOVER.md                # 本文
├── docs/
│   ├── 待办与已知问题.md        # 未完成 / bug / 避坑（干活前先扫一眼）
│   └── 版本历史.md              # 历代版本与更新日志
├── sources/
│   ├── v1.1.2/MoreCheck/      # 四个可构建工程（已品牌替换，包名 com.morecheck）
│   ├── v1.1.50/MoreCheck/
│   ├── v1.1.70/MoreCheck/
│   ├── v1.1.90/MoreCheck/     # ← 主线，改功能基本都在这里
│   ├── v1.2.0/                # 上游原样快照（只读参考，未 rebrand，含 .git → gitignored）
│   └── v1.4.0/                # 同上，上游最新正式版
├── dist/                      # 已签名安装包（四个版本，入库）
├── design/
│   ├── icon-concepts/         # 图标方案 A/B/C：SVG 源 + PNG + apply_icons.py + showcase.html
│   └── author-qr/             # 作者赞赏码原图（746×746）
├── keystore/                  # morecheck.jks（**不入库**，丢了无法覆盖升级）
├── keystore.properties.example
├── scripts/rebrand.py         # 上游快照 → MoreCheck 的重命名/版本号/签名脚本（不入库）
└── original-apks/             # 上游原版 1.1.2 / 1.1.50 安装包（不入库，仅比对用）
```

## 5. 代码架构（以 1.1.90 为基准）

### 5.1 技术栈

Kotlin 2.x + Jetpack Compose（Material3）+ Navigation、Room、DataStore(Preferences)、WorkManager、AlarmManager、CameraX。无广告/统计 SDK。

### 5.2 分层与关键类

| 位置 | 职责 |
| --- | --- |
| `MainActivity.kt` | 开屏同意门闸（协议）、通知权限、桌面快捷方式深链 |
| `MoreCheckApp.kt` | Application；排程 24h 周期云同步（`CloudSyncWorker`）与跨日 rollover |
| `ui/nav/TabDef.kt` + `TabNavHost.kt` | 底部四个入口（主页 / 统计 / 发现 / 我的）、全部路由枚举、检查更新地址 |
| `di/AppContainer.kt` | **依赖容器 + `Context.appContainer`**，全应用唯一取用入口；新增仓库先挂这里 |
| `data/CheckDatabase.kt`(v4) + `CheckDao.kt` | Room：任务、打卡记录、墓碑、待推记录 |
| `data/SettingsRepository.kt` | DataStore（`settings`）：主题、提醒、同步开关、登录态快照等 |
| `data/cloud/` | 云认证（验证码 / 密码）、同步（5 张表 + 存储桶）、云备份 |
| `data/*Store.kt` | 记事本、AI 对话、知识问答、游戏存档等本地 JSON/文件存储 |
| `reminder/` | AlarmManager 打卡提醒、`CloudSyncWorker`、`BootReceiver`、跨日 worker |
| `ui/lab/` | 实验室：开发者选项、记事本、智能体、番茄时钟、游戏、课程表、图标选择 |
| `domain/` | 纯函数（连续打卡、知识检索、游戏开屏取号），可单测 |

页面骨架纪律（踩过坑，别改）：

- 四个底栏入口**各自独立 NavController**，同一时刻只组合当前入口；**禁用** `HorizontalPager` / `AnimatedContent` / `Crossfade` 做入口间切换（曾导致底栏 item 随机消失）。
- 入口页自己的 `Scaffold` 必须写 `contentWindowInsets = WindowInsets(0,0,0,0)`，外层已统一让位，再扣一次会多一条空白。
- 新增页面：在 `TabDestination` 加枚举 → 在 `TabNavHost` 的 `when` 接线（漏接会编译报错，这是有意的）。

### 5.3 本地数据

- 打卡数据只在本地 Room；卸载即清空（升级必须覆盖安装）。
- 应用内"数据备份"可导出/导入整包 JSON（含记事本图片）；换机优先用它。
- 设置类数据在 DataStore，少数凭据文件已被系统备份规则排除。

### 5.4 云服务（1.1.90 完整，1.1.70 仅登录）

`data/cloud/CloudConfig.kt` 是唯一配置点：

```kotlin
ENDPOINT          = "https://morecheck.app.workbuddy.host"
PUBLISHABLE_KEY   = "wbpk_2KA020VX3CkCXSDkX332mn_..."
AUTH_BASE         = "$ENDPOINT/.cloud/auth"          // /v1/signin、/v1/signup、验证码
DB_BASE           = "$ENDPOINT/.cloud/database/rest" // PostgREST
STORAGE_BASE      = "$ENDPOINT/.cloud/storage"       // 记事本图片、整包备份
```

云端六张表：`checkin_tasks`、`checkin_records`、`notebook`、`app_settings`、`game_data`（五张同步表，均 `owner_id TEXT DEFAULT auth.uid()` + 逐用户 RLS）、`app_releases`（只读发布索引，客户端暂未消费）。

同步语义：`syncNow()` = 先 push 后 pull；逐行按 `updated_at` 取晚者（LWW），删除走墓碑表，各表独立水位线；**没有字段级合并**，同一行并发改会整行覆盖。登录成功后还要选一次"上传本机 / 下载覆盖 / 暂不处理"。

登录方式：手机号验证码注册/登录（新用户就地设密码）、手机号 + 密码登录。**没有找回密码入口**（见待办 P0-4）。

> ⚠️ 云端是**单会话制**：任何一次新登录都会把其它设备踢下线。调试发包时别随手登录。

### 5.5 图标体系（改图标必看）

默认图标走 Android 自适应图标：`mipmap-anydpi-v26/ic_launcher.xml` = 渐变背景 drawable + 透明前景 PNG（432×432，`drawable-xxxhdpi`）。当前默认是**方案 A**（蓝青渐变 + 进度环对勾）。

可替换图标共 11 套，靠 `activity-alias` 切换。**新增/改动一套要四处同步，缺一处就点不动或图标不换：**

1. `AndroidManifest.xml` 的 `<activity-alias>`（当前 11 个 `com.morecheck.icon.*`，全部 `LAUNCHER`）
2. `data/AppIconRepository.kt` 的 `BUILT_IN_ICON_IDS`
3. `ui/lab/IconPickerScreen.kt` 的资源映射与中文标签
4. `res/mipmap-anydpi-v26/ic_launcher_<名字>.xml` + 对应前景/背景资源

两版的集合不同，各 11 套，**别互相拷 id 列表**：

- 1.1.70：`default / blue / purple / orange / check / star / flame / leaf / tree / checklist / calendar`
- 1.1.90：`default / check / star / flame / leaf / tree / pine / snow / mint / checklist / calendar`（上游在此版删了蓝紫橙）

### 5.6 权限与隐私

1.1.90 共 9 项：通知、精确闹钟、开机自启、相机、网络、网络状态、悬浮窗、前台服务、前台服务(specialUse)。**定位权限已随天气功能一起删除**，不要再加回来。

首次启动必须同意《用户协议》《隐私政策》（正文内置在 `ui/mine/PlaceholderScreen.kt` 与 `ui/mine/LegalMarkdown.kt`），可撤回。隐私政策里已删掉天气/热搜/定位条款，版本号 V1.1.7、日期 2026-11-22 —— 改功能时**记得同步改这段文案**。

### 5.7 各版本能力差异

| 能力 | 1.1.2 | 1.1.50 | 1.1.70 | 1.1.90 |
| --- | --- | --- | --- | --- |
| 打卡 / 日历 / 记录 / 提醒 | ✓ | ✓ | ✓ | ✓ |
| 深浅色 / 动态取色 | ✓ | ✓ | ✓ | ✓ |
| 主题色（强调色） | — | — | ✓ | ✓ |
| 登录注册 | — | 界面（本地模拟） | 真实云端（验证码+密码） | 真实云端（验证码+密码） |
| 云同步 | — | — | **无**（仅登录） | ✓（5 表 + 图片） |
| 实验室 | — | — | 4 项（开发者选项/登录提示/记事本/智能体） | 13 项（含游戏、课程表、番茄时钟等） |
| 天气 | — | — | 已移除 | 已移除 |
| 热搜 / 今日待办 | — | — | 已移除 | 已移除 |
| 可替换图标 | — | — | 11 套 | 11 套 |
| 作者赞赏码 | 原图 | 原图 | 新码 | 新码 |

> 1.1.50 的源码来自上游 `v1.1.50` tag，但那个 tag 里其实是 1.1.42 beta 的代码，原版 1.1.50 APK 的「主题色」设置不在其中。这版只作历史归档，别拿它当功能基线（详见 [`docs/版本历史.md`](docs/版本历史.md) 2.3）。

## 6. 构建

### 6.1 环境（本机实测可用）

```bash
export JAVA_HOME="E:/gallery-tools/jdk-17.0.20.1+1"
export ANDROID_HOME="E:/Android/Sdk"          # build-tools 36.0.0
GRADLE="E:/gallery-tools/gradle-8.13/bin/gradle"
```

工程内自带 wrapper（Gradle 8.13），也可以 `cd sources/v1.1.90/MoreCheck && ./gradlew ...`。

### 6.2 签名

`keystore/morecheck.jks`（别名 `morecheck`）不随仓库提供；每个工程根目录需要一份 `keystore.properties`：

```properties
storeFile=../../../keystore/morecheck.jks
storePassword=***
keyAlias=morecheck
keyPassword=***
```

模板见 `keystore.properties.example`。**这个 jks 与口令必须离线备份**，丢了就没法给老用户发升级包。

### 6.3 出包

```bash
"$GRADLE" -p sources/v1.1.90/MoreCheck assembleRelease --console=plain
# 产物：sources/v1.1.90/MoreCheck/app/build/outputs/apk/release/app-release.apk
```

- 依赖已缓存时可加 `--offline`；首次构建需要联网解析。
- 改成只编 Kotlin（快速检查）：`compileReleaseKotlin`。
- **`assembleDebug` 通过 ≠ 能发版**：R8 走另一条路径，历史上有 debug 绿、release 炸的情况。收尾必须 `assembleRelease`。
- **模拟器开着别构建**：qemu 抢占 CPU，3 分钟会变 30 分钟以上。

### 6.4 出包后三查

```bash
BT="$ANDROID_HOME/build-tools/36.0.0"
"$BT/aapt2.exe" dump badging app-release.apk | head -3      # 包名 com.morecheck、版本号
"$BT/apksigner.bat" verify --print-certs app-release.apk     # 证书 SHA-256 必须是 d3da0ec0…d996
sha256sum app-release.apk                                    # 记下哈希
```

> 构建**不可复现**：同源码两次构建体积可能一样、哈希必定不同。只信哈希，别信"大小一样就是同一个包"。

## 7. 发布流程

> 现状：四个版本都已发布到 GitHub（`v1.1.2` / `v1.1.50` / `v1.1.70` / `v1.1.90`，后者为 Latest）。发 Release 用本机的 `gh`，**不要再用网页上传**。

### 7.1 从上游拉一个新版本（只在需要跟进时做）

1. 从私有仓库导出版本快照（`E:/download/app/Check` 是本机上对上游的克隆）：

   ```bash
   mkdir -p sources/vX.Y.Z
   git -C E:/download/app/Check archive <tag> | tar -x -C sources/vX.Y.Z/
   # 清掉不该进公开仓库的东西（密钥、上游文档、交付目录）
   rm -rf sources/vX.Y.Z/CheckInMate/keystore sources/vX.Y.Z/docs \
          sources/vX.Y.Z/dist sources/vX.Y.Z/web sources/vX.Y.Z/integration \
          sources/vX.Y.Z/debug sources/vX.Y.Z/README.md sources/vX.Y.Z/HANDOVER.md
   ```

   也可以 `git clone --depth 1 --branch <tag>` 后再删同样的目录。
2. 跑品牌替换脚本（会把目录/类名/字符串 `CheckInMate` → `MoreCheck`，并写 `versionCode` / `versionName` / `keystore.properties`）：

   ```bash
   python scripts/rebrand.py vX.Y.Z
   ```

   ⚠️ 脚本里的 `VERSIONS` 字典是**硬编码**的，新 tag 必须先加一行 `"vX.Y.Z": (<versionCode>, "<versionName>")`，否则会直接退出。

3. **人工核对云域名**。脚本会把 `checkinmate-sync.app.workbuddy.host` 一起替换成不存在的 `morecheck-sync…`，曾导致云登录整条链是死的。确认 `CloudConfig.ENDPOINT` 指向 `https://morecheck.app.workbuddy.host`。
4. 按需裁剪功能（如 1.1.90 删了天气/热搜/今日待办），同步删权限、隐私政策条款、实验室入口、路由与 `AppContainer`。
5. 接图标与作者二维码（见第 5.5 节与第 9 节）。
6. 编译、三查（第 6.4 节）。

### 7.2 常规发版（已有源码，只改功能）

1. **定版本号**：`app/build.gradle.kts` 里 `versionCode` 只增（当前 39 → 下一版 ≥ 40），`versionName` 沿用上游序列。
2. 改代码 → `assembleRelease` → 三查。
3. 复制产物：`cp app-release.apk dist/MoreCheck-<版本>.apk`，重算 SHA-256。
4. **同步文档**（容易漏，逐项打勾）：
   - [ ] `README.md` 版本表与哈希
   - [ ] `docs/版本历史.md` 追加本版条目
   - [ ] 应用内 `ui/settings/ChangelogScreen.kt` 的 `RELEASES`（面向用户，3~6 条，一行一件事，末条写"修复了若干细节问题"）
   - [ ] 若改了功能范围：隐私政策正文、权限清单、README 功能列表
5. 提交推送：

   ```bash
   git add -A
   git status --short        # 确认没有 sources/v1.2.0、v1.4.0、keystore、keystore.properties
   git commit -m "release: <版本>"
   git tag v<版本>
   git -c http.sslBackend=openssl push origin main --tags
   ```

   仓库身份用 `MoreCheck <morecheck@users.noreply.github.com>`；本机默认 schannel 会 TLS 握手失败，必须带 `http.sslBackend=openssl`。
6. **建 GitHub Release——用 `gh`，一条命令**（别再走网页上传，那个流程又慢又容易把路径粘进正文）：

   ```bash
   gh release create v<版本> dist/MoreCheck-<版本>.apk \
     -t "MoreCheck <版本>" \
     -F notes.md --latest
   ```

   - 正文也可以直接 `-n "- 第一条\n- 第二条"`；长文本建议写进 `notes.md` 用 `-F` 读，省得和 shell 引号较劲。
   - `--latest` 只给最新版，补发历史版本时**不要**带。
   - 已发布的 Release 要换资产：`gh release upload v<版本> dist/MoreCheck-<版本>.apk --clobber`；改正文：`gh release edit v<版本> -F notes.md`。
   - `gh` 环境：`C:\Program Files\GitHub CLI\gh.exe`（winget 包 `GitHub.cli`），登录态在 Windows keyring（账号 `SciF-Lin`，scopes `repo` 等），**不需要再授权**。若新终端里 `gh` 找不到，重开终端刷新 PATH，不要重新安装。
7. 发布后自检：

   ```bash
   gh release view v<版本> --json tagName,assets   # 资产名与 size 对得上
   gh release list                                 # Latest 标记在这一版
   gh release download v<版本> -R SciF-Lin/MoreCheck -p "*.apk" -D /tmp/verify
   sha256sum /tmp/verify/*.apk                     # 与 dist/ 一致
   ```

   > `gh release download` 在仓库目录里可以省略 `-R`；在别处执行**必须**带 `-R SciF-Lin/MoreCheck`，否则报 `fatal: not a git repository`。
   - 真机冒烟：启动 → 打卡 → 统计 → 我的 → 关于 → 检查更新（能跳到新版页面）。
8. （可选，未来）在云端 `app_releases` 表补一行：`version_name / version_code / apk_url / size_bytes / sha256 / published_at`。当前客户端还没读这张表。

### 7.3 发布纪律

- **一个版本号只对应一个包**。同版本修 bug 就重出并替换同名资产与 `dist` 文件，不新造 tag。
- **五个版本同包名同签名**，用户可跨版本覆盖升级；任何一项变了就必须在 README 里写清"需卸载重装"。
- 推代码与发 Release 尽量同批完成，避免"代码在线上、包没地方下"。
- 发版前确认 `sources/v1.2.0` 没被 `git add`；`sources/v1.4.0/MoreCheck` 应作为干净工程提交，其上游附带目录不应出现。

## 8. 硬纪律与踩过的坑

1. 改完代码构建前先删 `app/build/kotlin`：陈旧增量缓存会让你得到"构建成功、行为是旧的"。
2. 每轮收尾必须 `assembleRelease`，别只跑 debug。
3. 构建不可复现，只信哈希。
4. Compose 的 `initialValue` 是"流还没发射时显示什么"，不是业务默认值；用 `null` 哨兵，别写出"冷启动跳过锁屏"。
5. 底栏四入口独立 NavController，禁用 Pager/AnimatedContent/Crossfade 切换入口。
6. 入口页 Scaffold 的 `contentWindowInsets` 归零。
7. 云端单会话制，新登录踢设备；调试优先复用已登录会话。
8. `rebrand.py` 会连域名一起替换——拉新版本后必须人工核对 `CloudConfig`。
9. 模拟器开着别构建。
10. 别把上游私有资产（keystore、docs、HANDOVER、dist 说明）带进公开仓库；`sources/v1.2.0` 只在本机，`sources/v1.4.0` 只提交干净的 `MoreCheck/` 工程。
11. 新增联网能力要考虑隐私政策与权限文案，本应用**不加统计/广告 SDK**。
12. 发布前确认 `README.md` 的哈希与实际文件一致（用 `sha256sum` 复核，别手抄）。

## 9. 常见改动速查

| 要做什么 | 改哪里 |
| --- | --- |
| 换默认图标 | `design/icon-concepts/apply_icons.py` 或直接替换 `mipmap-anydpi-v26/ic_launcher.xml` + 前景 PNG |
| 加一套可替换图标 | 第 5.5 节的四处同步 + manifest alias |
| 换作者赞赏码 | 替换 `res/drawable-nodpi/ic_support_qr.png`（建议 640×640 以上、正方形） |
| 改检查更新地址 | `ui/nav/TabNavHost.kt` 的 `RELEASES_URL`（1.1.70 / 1.1.90 各有一份） |
| 换云环境 | `data/cloud/CloudConfig.kt`，同时确认新环境的表结构与 RLS |
| 改应用名 | `res/values/strings.xml` 的 `app_name` + 各 alias 的 `android:label` |
| 加实验室功能 | `ui/lab/LabScreen.kt` 的 `LAB_ITEMS` + `LabRealPage` + `TabNavHost` 路由 |
| 升版本号 | `app/build.gradle.kts` 的 `versionCode` / `versionName` |
| 改隐私政策 | 应用内协议正文（`ui/mine/PlaceholderScreen.kt` / `LegalMarkdown.kt`）+ `README.md` 口径 |

---

有任何与本文不符的地方，以**代码和 `git log` 为准**，并顺手把本文改对——文档过期比没有文档更坑。
