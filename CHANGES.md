# DeepSeek Harness Android · 移动端优化改动清单

> 本 PR 基于原作者 v0.1.0 源码，聚焦两类问题：
> **① 真机启动稳定性（node 运行时 + 服务器保活）② 移动端 UI 适配（竖屏/设置页/触摸）**
> 改动文件：`AndroidManifest.xml` / `build.sh` / `MainActivity.java` / `mobile-patch/*` / `README.md`

---

## 一、启动稳定性（修复真机 ERR_CONNECTION_REFUSED）

### 1.1 Node 运行时 soname 符号链接丢失（致命，已修复）
- **根因**：`build.sh` 用 `jar cMf` 打包 payload，**把符号链接全部压平成普通内容**；解压后
  `runtime/lib/` 只剩带版本号的文件（`libz.so.1.3.2` 等），`libz.so.1`、`libcrypto.so`、
  `libssl.so`、`libsqlite3.so.0` 全部缺失 → node 启动即报
  `CANNOT LINK EXECUTABLE: library "libz.so.1" not found`。
- **修复**：`build.sh` 在组装 payload 时**按 LINKS.txt 把 soname 目标复制成同名实体文件**
  （不依赖设备是否支持软链接，动态加载器按名字找文件即可）。经真机验证 node 正常启动。
  - 代价：payload 增大 ~34MB（版本化 .so 的实体副本）。

### 1.2 服务器保活：看门狗 + WebView 自动重试（新增）
- **根因**：原版 `webView.loadUrl()` 只执行一次；若 node 未就绪或进程被系统回收，页面永久停在
  `net::ERR_CONNECTION_REFUSED`，且没有任何恢复手段。
- **修复**（`MainActivity.java`）：
  - **WebView 失败重试**：主框架加载失败时每 2.5s 自动 `loadUrl(URL_HOME)`，直到服务器就绪（上限 120 次）。
  - **node 看门狗**：后台线程每 5s 检查 `healthOk()` + `nodeProcess.isAlive()`；node 死亡且服务不可用
    时自动重启引擎并刷新页面（20s 防抖避免风车重启）。

### 1.3 移动端 CSS/JS 强制注入（保证生效，不再依赖服务器文件）
- **根因**：前端 dist 通过 index.html 引用 mobile.css，但服务器文件/缓存/解压策略不可控，
  实测部分设备上样式根本没加载。
- **修复**：`build.sh` 把 `mobile.css`/`mobile.js` **直接打进 APK assets**；
  `MainActivity.onPageFinished` 用 `evaluateJavascript` + base64 **强制注入 WebView**
  （幂等：已存在同名元素则跳过，避免重复注入导致监听器/观察器堆积）。

---

## 二、移动端 UI 适配

### 2.1 解锁竖屏（AndroidManifest.xml）
- `android:screenOrientation="sensorLandscape"` → `"unspecified"`（自由旋转）。
- 版本号升至 `versionCode=2 / versionName=1.1.0`。

### 2.2 mobile.css 完全重写（修复"死代码"）
- **根因**：原 mobile.css 使用的类名（`gdEzaW_`、`hHd-Xa_`、`pbvGtq_`、`qSYn7G_` 等）在
  真实前端构建（0.1.0-rc.6 dist）中**不存在**，全部规则无效。
- **修复**：改用从真实构建提取的类名（`_rail_1hk8w`、`_wrap_1ao1y`、`_answer_d4nqi`、
  `_markdown_1nba0`、`_block_10eou`、`_item_19372` 等）：
  - 触摸优化（点击目标 ≥44px、16px 输入字号防聚焦缩放）
  - 竖屏专项：内容全宽、输入区加大、侧栏图标收紧
  - 设置页排版：菜单项加高、说明文字行距 1.75
  - 鲸鱼蓝皮肤（`--dsw-alias-brand-primary: #4D6BFE`）

### 2.3 mobile.js：竖屏交互重构
- 软键盘适配：VisualViewport + translateY（竖屏横屏通用，rAF 节流）。
- **竖屏自动收起侧边栏**：按文字找到「收起侧边栏」按钮并点击（不依赖哈希类名，收起后按钮变
  「展开」不会误点）。
- **设置页「点击跳转」**（竖屏）：左侧菜单全宽列表，点击菜单项 → 内容**全屏显示** + 「← 返回」按钮；
  彻底解决竖屏下"左右分栏 → 内容窄到文字竖排"的难看排版。
- **MutationObserver 持续监听**：React 重渲染重建 DOM 后自动重新应用布局（幂等 + 防抖），
  解决"切换几次设置项后退化回原布局"的问题。
- **幂等保护**：`window.__ddMobile` 全局标记 + 注入侧 getElementById 检查，防止重复挂载。

### 2.4 退出交互（MainActivity.java）
- 右上角常驻「退出」浮动按钮（确认对话框后退出）。
- 系统返回键：有历史先 `goBack()`（可关侧边栏），无历史弹确认退出。

---

## 三、验证情况

| 项目 | 结果 |
|---|---|
| node 启动（soname 修复） | ✅ 真机验证 node 正常运行 |
| ERR_CONNECTION_REFUSED 恢复 | ✅ 看门狗 + 自动重试生效 |
| 竖屏自由旋转 | ✅ 已解锁 |
| 设置页「点击跳转」 | ✅ 稳定（MutationObserver 常驻） |
| 鲸鱼蓝皮肤注入 | ✅ 100% 生效（强制注入） |
| 退出按钮 / 返回键 | ✅ |
| 依赖完整性 | ✅ payload 内 239 个依赖齐全（云端实测 dsh --version 可跑） |

## 四、构建与注意事项

- 构建：`bash android-app/build.sh`（需 android.jar、java-17、aapt/d8/zipalign/apksigner、release.jks）。
- 签名：本 PR 未包含签名密钥；安装包需自行签名。
- targetSdk 保持 28（≥29 会导致 node 二进制 EACCES）。
- 首次启动需解压 payload（2 万+ 文件，约 1-3 分钟），期间勿切后台。
- 外部存储权限未授予时回退内部 dshroot；授予后外部优先（已有文件不覆盖）。
