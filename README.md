# MilliClock 毫秒悬浮钟

安卓毫秒级悬浮时钟 / 抢购倒计时工具。零第三方依赖，标准 Gradle 工程，APK 仅约 45 KB。

## 功能

- **悬浮毫秒时钟**：悬浮于所有应用之上，可拖动、点按呼出菜单，支持触摸穿透（手指操作完全穿过悬浮窗）
- **倒计时模式**：目标时间精确到毫秒；最后 10s 黄 → 5s 橙 → 1s 红 → 归零变绿并震动
- **NTP 网络校时**：自实现 SNTP 客户端（RFC 2030 四时间戳法），消除网络延迟影响，多服务器择优（阿里云/腾讯/NTP池/Windows），UDP 被封时回退 HTTP Date 兜底
- **本地走时**：基于 `SystemClock.elapsedRealtime()` 锚定，不受用户改系统时间影响，显示零网络开销
- **配置页**：模式/目标时间（+1分/+5分/下个整点/自定义）/字号/刷新率/穿透/震动，全部应用内实时生效，无需重新编译
- **事件提醒**：预设开售时间（单次/每天/每周几），到点前 N 分钟发系统级提醒通知（AlarmManager.setAlarmClock），点通知「开始倒计时」直接唤起悬浮窗；开机自动重新注册
- **轻量**：无广告、无推送、无常驻全家桶，运行内存占用约 20MB

## 构建

标准 Android Gradle 工程：AGP 8.2.2 + Gradle 8.4（wrapper 已配置腾讯云镜像）+ JDK 17，`compileSdk 34`。

```powershell
# 命令行构建（首次会自动下载 Gradle 与依赖，已配国内镜像）
.\gradlew.bat assembleDebug      # 调试包 app/build/outputs/apk/debug/
.\gradlew.bat assembleRelease    # 签名正式包 app/build/outputs/apk/release/
```

更推荐直接用 **Android Studio** 打开项目根目录，等 Gradle Sync 完成后：
- 布局可在 Design 模式实时预览
- 连接手机点 Run ▶ 即可安装运行、看 Logcat

签名：复用根目录的 `keystore.jks`（与旧版 build.ps1 相同密钥，可覆盖安装 v1.0/v1.1）。换机器克隆后把 keystore.jks 放回根目录即可。

构建管线（Gradle 内部完成）：`aapt2`（编译资源+链接 Manifest）→ `javac`（编译源码）→ `d8`（转 DEX）→ 打包对齐 → `apksigner` 签名。

## 目录结构

```
MilliClock/
├── app/
│   ├── build.gradle                 # 应用模块配置（版本号/签名/兼容性）
│   └── src/main/
│       ├── AndroidManifest.xml      # 权限与组件声明
│       ├── java/com/milliclock/
│       │   ├── MainActivity.java        # 配置页（模式/时间/外观/行为/启动）
│       │   ├── EventListActivity.java   # 事件列表（预设开售时间+提醒）
│       │   ├── Event.java / EventStore.java  # 事件模型与 JSON 存储+闹钟调度
│       │   ├── AlarmReceiver.java / BootReceiver.java  # 到点提醒 / 开机重注册
│       │   ├── FloatingService.java     # 前台服务 + 悬浮窗渲染/交互/倒计时
│       │   ├── TimeSync.java            # SNTP 校时（四时间戳法）+ HTTP 兜底
│       │   └── Prefs.java               # SharedPreferences 轻量配置存取
│       └── res/                         # 布局 / 字符串 / 图标
├── build.gradle / settings.gradle   # 工程级配置（AGP 版本/镜像仓库）
└── README.md
```

## 安装使用

1. 安装 APK，打开 App 按提示授予「悬浮窗权限」
2. MIUI 额外建议：应用详情 → 其他权限 → 开启「后台弹出界面」「锁屏显示」；省电策略设为「无限制」
3. 点「立即校时」，设置目标时间，点「启动悬浮窗」，切到抢购页面即可

## Roadmap

- [x] 事件列表：预设抢购事件，到点前 N 分钟提醒（支持每天/每周几重复）
- [ ] 桌面小组件

## 说明

- 本项目仅供个人学习与提醒用途，不含自动点击功能，使用时请遵守平台规则
- 签名密钥 `keystore.jks` 与构建产物不入库（见 `.gitignore`），删掉 keystore 后重新构建会生成新的，但升级安装需要同签名
