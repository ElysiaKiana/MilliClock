# MilliClock 毫秒悬浮钟

安卓毫秒级悬浮时钟 / 抢购倒计时工具。零第三方依赖，无 Gradle，单脚本构建，APK 仅约 33 KB。

## 功能

- **悬浮毫秒时钟**：悬浮于所有应用之上，可拖动、点按呼出菜单，支持触摸穿透（手指操作完全穿过悬浮窗）
- **倒计时模式**：目标时间精确到毫秒；最后 10s 黄 → 5s 橙 → 1s 红 → 归零变绿并震动
- **NTP 网络校时**：自实现 SNTP 客户端（RFC 2030 四时间戳法），消除网络延迟影响，多服务器择优（阿里云/腾讯/NTP池/Windows），UDP 被封时回退 HTTP Date 兜底
- **本地走时**：基于 `SystemClock.elapsedRealtime()` 锚定，不受用户改系统时间影响，显示零网络开销
- **配置页**：模式/目标时间（+1分/+5分/下个整点/自定义）/字号/刷新率/穿透/震动，全部应用内实时生效，无需重新编译
- **轻量**：无广告、无推送、无常驻全家桶，运行内存占用约 20MB

## 构建

依赖：JDK 17、Android build-tools 34、Android platform android-34

```powershell
# 1. 编辑 build.ps1 顶部，改为本机路径
$sdk  = "D:\AndroidTerminal#1-2"        # Android SDK 根目录
$env:JAVA_HOME = "D:\Java\javajdk\jdk-17.0.1"

# 2. 构建并签名（首次运行自动生成调试密钥库）
powershell -File build.ps1
# 产物：MilliClock.apk
```

构建管线：`aapt2`（编译资源+链接 Manifest）→ `javac`（编译源码）→ `d8`（转 DEX）→ 打包对齐 → `apksigner` 签名。

## 目录结构

```
MilliClock/
├── app/
│   ├── AndroidManifest.xml          # 权限与组件声明
│   ├── java/com/milliclock/
│   │   ├── MainActivity.java        # 配置页（模式/时间/外观/行为/启动）
│   │   ├── FloatingService.java     # 前台服务 + 悬浮窗渲染/交互/倒计时
│   │   ├── TimeSync.java            # SNTP 校时（四时间戳法）+ HTTP 兜底
│   │   └── Prefs.java               # SharedPreferences 轻量配置存取
│   └── res/                         # 布局 / 字符串 / 图标
├── build.ps1                        # 一键构建脚本（无 Gradle）
└── README.md
```

## 安装使用

1. 安装 APK，打开 App 按提示授予「悬浮窗权限」
2. MIUI 额外建议：应用详情 → 其他权限 → 开启「后台弹出界面」「锁屏显示」；省电策略设为「无限制」
3. 点「立即校时」，设置目标时间，点「启动悬浮窗」，切到抢购页面即可

## Roadmap

- [ ] 事件列表：预设抢购事件，联动提醒（方案讨论中）
- [ ] 桌面小组件

## 说明

- 本项目仅供个人学习与提醒用途，不含自动点击功能，使用时请遵守平台规则
- 签名密钥 `keystore.jks` 与构建产物不入库（见 `.gitignore`），删掉 keystore 后重新构建会生成新的，但升级安装需要同签名
