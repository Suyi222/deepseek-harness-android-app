# DeepSeek Harness 手机版（Android）

把 DeepSeek Harness（DSH）内核 + 官方前端打包成可安装到 Android 手机的 App，并针对手机做移动端适配与系统能力插件。

- 内核：`@deepseek-ai/dsh` 0.1.0-rc.6（保留插件生态 + RPC API）
- 前端：DSH 原生界面（`dsh-web-frontend` dist），只做移动端适配
- 品牌：DeepSeek Harness，鲸鱼图标
- 包名：`com.deepseek.harness`

> ⚠️ 这是源码与配置仓库，**不含 APK 二进制、签名密钥（release.jks）、node 运行时、payload.zip、凭证文件**。
> 📦 安装包（DeepSeekHarness.apk）与 node 运行时/DSH 内核分块包见 [Releases](https://github.com/woaiys3/deepseek-harness-android-app/releases)；构建源码前需自行准备 DSH 内核与 node 运行时。

## 目录结构

```
android-app/             APK 构建工程
├── build.sh             一键打包脚本
├── AndroidManifest.xml  包名/targetSdk(28)/竖屏横屏自由旋转/Shizuku 声明
├── libs/                Shizuku 官方 aar（api/provider/aidl 13.1.5）
├── res/                 图标 + 字符串资源
└── src/.../MainActivity.java   Android 原生壳（权限页/加载页/引擎启动）

mobile-patch/            移动端适配（注入 DSH 前端，不覆盖原生代码）
├── inject.sh            注入脚本（mobile.css + mobile.js 到 dist）
├── mobile.css           触摸优化 + 竖屏适配 + 插件管理页 UI 适配（v0.2：类名改用真实构建类名）
└── mobile.js            软键盘适配（VisualViewport 方案，竖屏横屏通用）

plugins/                 手机端自定义 DSH 工具插件
├── dsh-tool-shizuku/    特权 shell（Shizuku 通道）
└── dsh-tool-android/    结构化系统操作（包管理/应用/设置/截图/输入）

dsh-patches/             DSH 源码补丁归档 + overlay
├── README.md            架构/升级/插件/踩坑文档
├── apply.sh             重新应用源码补丁
└── overlay/             改好后的源码文件

config/cordis.patch.yml  DSH 组合配置（禁原生模块 + 插入 bash-local/shizuku/android 插件）

docs/开发指南.md            项目开发指南（架构/常用命令/注意事项）
```

## 构建说明

详见 `docs/开发指南.md` 第六节「常用命令」与第七节「注意事项」。

关键点：
- `targetSdk` 必须保持 **28**（≥29 会导致 node 二进制 EACCES 起不来）
- 需准备 `runtime/`（node v26 + 依赖库）和 `dshroot/`（DSH 内核）才能打完整 APK
- `build.sh` 会自动注入 mobile.css/mobile.js，并做 API Key 安全检查

## 手机端插件

| 插件 | 功能 |
|---|---|
| `dsh-tool-shizuku` | 特权 shell 命令（异步 spawn + env 消毒 + dex 只读自愈） |
| `dsh-tool-android` | 结构化系统操作：包管理/应用管理/系统设置/截图/模拟输入 |

## 许可证

本项目源码采用 [MIT](LICENSE) 许可证。

- 依赖的 DSH 内核（@deepseek-ai/dsh）为 MIT；Shizuku SDK 为 Apache-2.0；node 运行时为 MIT。
- 仓库不含签名密钥与凭证；安装包与运行时见 [Releases](https://github.com/woaiys3/deepseek-harness-android-app/releases)。
