# 腕骑地图

把骑行导航搬到手表上。

手机端粘贴一条高德地点的分享链接 → 通过蓝牙推送到手表 → 手表端直接算路并开始导航，
全程不用在手表那块小屏幕上选点。导航界面是自研 HUD（转向图标、距离、路名、剩余里程），
转弯与偏航用震动提醒。

## 组成

| 模块 | 运行端 | 说明 |
|---|---|---|
| `app/` | 手表端（Wear OS 4+） | 等待手机推送目的地 → 高德算路 → 自研 HUD 导航 |
| `phone/` | 手机端（Android 8.0+） | 粘贴高德分享链接（自动解析坐标）→ 通过 BLE 推送目的地 |

两端 `applicationId` 相同（`io.github.lishijiedev.wanqimap`），系统据此把它们识别为「同一应用的手机版 / 手表版」。

手机端**刻意不依赖高德 SDK**，也不内置任何 Key —— 它只做链接解析与蓝牙发送。
不过它**必须拿到你自己的 Key** 才能工作：Key 随目的地一起下发给手表，手表在创建导航引擎之前注入。

## 功能

- [x] 骑行路线导航，自研 HUD（转向图标 / 距离 / 路名 / 剩余里程）
- [x] 手机端推送目的地，手表端一键开始导航
- [x] 转弯与偏航震动提醒
- [x] 导航期间前台服务保活 + 表盘「导航进行中」指示器
- [ ] 个人账号、常用地点（尚未实现）

## 使用前必读：高德 Key 要自己申请

**本项目 —— 无论是源码还是安装包 —— 都不包含任何高德 Key。**
高德的 Key 与「包名 + 签名」绑定，只能由使用者本人申请，无法预先内置。

### 1. 申请

在 [高德开放平台](https://lbs.amap.com/) 创建应用，添加 Key 时按下表填写：

| 控制台字段 | 值 |
|---|---|
| 服务平台 | **Android 平台** |
| Package | `io.github.lishijiedev.wanqimap` |
| 发布版安全码 SHA1 | 见下 |
| 调试版安全码 SHA1 | 见下 |

SHA1 填什么，取决于你怎么拿到这个 App：

- **直接安装本仓库 Releases 里的 APK** —— 签名是固定的，填这个：
  ```
  09:FB:7A:FA:C1:53:EE:24:4A:42:72:E6:26:AA:32:A1:BC:82:98:7D
  ```
  （签名指纹是公开信息，不含私钥，可以放心公开。）

- **自己 clone 源码编译** —— 你用的是本机的调试密钥，指纹与上面不同，填你自己的：
  ```bash
  keytool -list -v -keystore ~/.android/debug.keystore \
          -alias androiddebugkey -storepass android -keypass android
  ```
  Windows 下密钥库路径为 `%USERPROFILE%\.android\debug.keystore`。

> Package **区分大小写**；Key、包名、SHA1 三者任意一项对不上，地图就是白屏，
> 而且只报一个很含糊的 `INVALID_USER_SCODE`，所以请逐字核对。

### 2. 填到手机端

装好手机端后，在界面顶部填入 Key 并保存（32 位十六进制）。保存后这块会收成一行，
需要改动时点它右侧的「修改」—— 改完记得再点一次保存，否则推送出去的仍是旧 Key。

手表端没有输入界面 —— Key 会随目的地一起通过蓝牙下发，在手表创建导航引擎之前注入。
**没填 Key 时手机端不会允许推送**，会提示先填写。

## 安装

从本仓库 **Releases** 下载两个安装包（作者本地编译签名，签名指纹就是上文那个 SHA1）：

- **手表端 `app-release.apk`** —— 需要 Wear OS 4 或更新版本（Android 13 / API 33 起）。
  用 `adb install` 或在手表上用文件管理器安装。
- **手机端 `phone-release.apk`** —— Android 8.0 及以上。

> 两端 `applicationId` 相同，是同一个应用的两个版本，**必须分别装到对应设备**：
> 手表装 `app-release.apk`，手机装 `phone-release.apk`。
> 装反了（尤其把手表端装到手机上）会看到「等待手机推送目的地」的界面，且没有 Key 输入框。

首次启动会请求权限：两端都需要蓝牙权限（手表端显示为「附近的设备」）；
手表端还会请求通知权限，用于表盘上的「导航进行中」指示器。

## 构建

需要 **JDK 17**（AGP 8.5.x 不接受 JDK 21 及以上）。

### 第一步：补上高德 SDK

**本仓库不包含高德 SDK**（版权归高德，本项目不再分发）。clone 之后先自行下载并放入：

| 下载到的文件 | 放到 |
|---|---|
| `AMap3DMap_*_AMapNavi_*_AMapSearch_*_AMapLocation_*.jar` | `app/libs/` |
| `libAMapSDK_NAVI_*.so` | `app/src/main/jniLibs/arm64-v8a/` 与 `app/src/main/jniLibs/armeabi-v7a/` |
| `libneonui_shared.so`、`libneonuijni_public.so` | 同上两个目录 |

在 [高德开放平台](https://lbs.amap.com/) 的文档中心找到「Android 导航 SDK」的下载页，
选中导航 SDK 后取上表列出的 jar 与 so。

这两个目录已经写进 `.gitignore`，你补进去的文件不会被提交，也不影响本地编译。

### 第二步：编译

```bash
./gradlew :app:assembleDebug      # 手表端
./gradlew :phone:assembleDebug    # 手机端
```

仓库不配置 CI —— 云端构建机上没有高德 SDK，跑了也出不了包。
发布用的 APK 由作者本地编译后上传到 Releases。

## 合规声明

使用本项目（源码或安装包）即表示你已知悉并同意：

1. **Key 由你自己申请**，并自行遵守[《高德地图开放平台服务协议》](https://lbs.amap.com/pages/terms/)
   及相应的配额与计费规则。本项目不提供、不出借、不转授权任何 Key。
2. **商业用途请自行向高德购买技术服务许可。** 本项目不授予任何商业使用权，也不代表高德对你
   商业使用的授权。本项目本身免费提供，不含广告与付费功能。
3. 面向骑行与步行场景，**请勿用于车载导航**。
4. 高德 SDK 的版权与许可归高德所有，与本项目代码的许可证相互独立。
   **本仓库不分发 SDK 本身**（也不随源码提供），请自行从高德官网获取，并遵守其许可条款。

## 许可

源代码以 [GPL-3.0](./LICENSE) 发布。
