# 作业流程（WORKFLOW）

> 本项目的固定作业流程与版本管理约定。每次迭代必须遵守，保证可回滚、可追溯。

## 一、固定作业流程（七步）

1. **需求**：在 WORKLOG 顶部待办区登记本次迭代目标（改什么、为什么、验收标准）。
2. **调研**：涉及未知类名/参数名时先探测/抓包，结论写进 WORKLOG「结论与依据」。
3. **编码**：改代码。hook 改动必须走 `tryHook` 包装（单点失败不连累全局）；设置项改动必须同步 `Prefs` 与设置页。
4. **编译校验**：本机跑 `tools/build-check\verify.bat`（javac 全量校验），通过后再进下一步；Android Studio 构建由测试机侧执行。
5. **版本备份（改动前）**：若已有已发布版本，先归档当前版本再动代码（见「三、版本备份」）。
6. **发版（双件套，必做）**：bump `app/build.gradle` 的 versionName/versionCode → 运行 `tools\build-apk.ps1` 出签名 APK → 更新 CHANGELOG → 归档**源码 zip + APK** 两个文件到 `docs/versions/`（详见「六」）。
7. **真机验证 + 回流**：按 README 测试步骤验证；探测日志与抓包结论回写 WORKLOG，驱动下一轮迭代。

## 二、版本号约定

- 语义化版本 `MAJOR.MINOR.PATCH`：
  - MAJOR：伪装方案/声明格式不兼容变更（如 scope.list 结构、模块 ID 变化）；
  - MINOR：新增 hook 层、新增机型预设、新增设置项；
  - PATCH：hook 点修正、缺陷修复、文档更新。
- `versionCode = MAJOR*1000 + MINOR*100 + PATCH`（v1.2.3 → 1203），随版本号同步 bump。
- 当前版本记录在 `app/build.gradle` 与 README 头部，两处必须一致。

## 三、版本备份

- 备份目录：`docs/versions/`，每个版本归档一对文件：
  - 源码包 `v{X.Y.Z}-{YYYYMMDD}.zip`（如 `v1.0.0-20260925.zip`）；
  - 安装包 `v{X.Y.Z}-{YYYYMMDD}.apk`（与 `dist/` 内最新构建一致，可直接安装）。
- 时机：
  1. **每次发版后**：归档该版本完整源码（必做）；
  2. **大改前**（MAJOR/MINOR 动刀前）先归档当前稳定版（推荐）。
- 打包方式（工作区根目录执行，排除本地缓存）：

  ```bat
  tar -a -c -f docs\versions\v1.0.0-20260925.zip --exclude .gradle --exclude build --exclude "app\build" --exclude "tools\build-check\cache" --exclude "tools\build-check\out" --exclude docs\versions .
  ```

- 回滚：解压对应 zip 覆盖工作区即可（docs/versions 本身不在包内，历史不受影响）。

## 四、APK 构建（免 Gradle 流水线）

本机无需 Android SDK/Gradle，一条命令从源码直出**签名可安装 APK**：

```bat
powershell -ExecutionPolicy Bypass -File tools\build-apk.ps1
```

- 流水线：javac → aapt2 link（manifest 注入 package/version）→ d8 出 dex → 组装 APK（含 META-INF/xposed 声明）→ zipalign → apksigner v1+v2 签名 → 自验。
- 前置（一次性）：`tools\build-check\cache\`（JDK 17、android.jar、api/service/annotation/kotlin jars）+ build-tools 目录（含 aapt2.exe/zipalign.exe/d8.jar/apksigner.jar，默认取 `%TEMP%\mfm_bt\bt`，可用 `-BuildToolsDir` 指定）。
- 版本号/包名/minSdk 全部自动读自 `app/build.gradle`，**改版本只需改 build.gradle**。
- 签名密钥：`tools\signing\mifitmask.p12`（口令 `mifitmask2026`，别名 mifitmask）。**所有版本必须复用同一密钥**，否则用户升级时需先卸载（LSPosed 作用域与设置会丢）。
- 产物：`dist\MiFitDeviceMask-v{版本}.apk`。
- 仅做语法/API 校验不出包用 `tools\build-check\verify.bat`。

## 五、真机验证流程（每次发版必做）

1. 安装 APK → LSPosed 启用模块 → 作用域勾选目标包 → 重启手机。
2. 打开模块设置页确认「已连接 LSPosed 服务」，选择目标机型保存。
3. 强停小米运动健康 → 重开 → 设备页断开重连手环 → 进入表盘商店。
4. 抓日志：`adb logcat -s LSPosed-Bridge:V`（或 LSPosed 管理器日志页按 tag `MifitMask` 过滤）。
5. 记录：hooked/hook failed 行、probe 输出、商店实际表现；回写 WORKLOG。

## 六、缺陷处理约定

- hook 崩溃目标 App：立即在设置页关总开关（hook 侧放行原逻辑），再定位；必要时停用模块。
- 类名漂移导致的 hook failed：把失败类名与目标 App 版本号记入 WORKLOG，用 ProbeHooks 探测替代点，候选表修正后出 PATCH 版本。
- **放宽 hook 执行条件的发布纪律**：任何"让某段 hook 代码被更早/更频繁执行"的改动，其下游路径此前可能从未真实运行过——发布前必须在默认配置下真实触发一次（如逛一遍商店），否则潜伏缺陷会以目标 App 闪退的形式暴露（v1.1.5→v1.1.6 教训）。
