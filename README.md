# MiFit 机型伪装（小米运动健康机型伪装模块）

基于 **libxposed API 102** 的 LSPosed 模块：注入小米运动健康（国内 `com.mi.health` / 国际 `com.xiaomi.wearable`）进程，修改 App 内的设备机型档案，使**表盘商店按目标机型返回对应表盘市场**。

> ⚠️ 仅供测试与学习研究使用。伪装可能导致表盘与手环实际屏幕不匹配（安装失败/显示异常），请知悉风险后再启用。

**当前版本：v1.0.0**（versionCode 1000，变更记录见 [docs/CHANGELOG.md](docs/CHANGELOG.md)）

## 伪装原理（分层）

| 层 | 开关 | 说明 |
| --- | --- | --- |
| 第 1 层 · 本地设备档案 | 默认开 | 覆写 `BluetoothDevice.getName()` 与设备档案候选类的 getter（model / 设备名 / 数值标识），App 内所有"读机型"的点拿到目标机型 |
| 第 2 层 · 商店请求参数重写 | 实验默认关 | 注入 OkHttp 拦截器，改写 watchface 相关请求的 query/JSON 机型字段（参数键名未经实机全量验证，建议先抓包确认） |
| 探测日志 | 调试开关控制 | 输出候选类/方法签名/取值到 Xposed 日志，用于真机验证后固化 hook 点 |

类名随 App 版本混淆漂移，本模块采用**多候选 + 失败隔离**策略（单点失败只记日志）；真机探测日志回落后把有效点固化进候选表（见 ROADMAP v1.1）。

## 环境

- 已 root 的安卓设备 + **支持 libxposed API 101/102 的 LSPosed 框架**（较新的 LSPosed 发行版或其活跃分支）。
- 小米运动健康 App（国内版或国际版，包名二选一，均在作用域内）。

## 构建与安装

**直接安装（推荐）**：仓库已附带编译好的签名安装包

```
dist\MiFitDeviceMask-v1.0.0.apk
```

传到设备安装即可（v1+v2 签名，测试密钥；后续版本复用同一密钥，可覆盖升级无需卸载）。

**自行构建**（无需 Android SDK / Gradle）：

```bat
powershell -ExecutionPolicy Bypass -File tools\build-apk.ps1
```

流水线：javac → aapt2 → d8 → 组装 → zipalign → apksigner 签名，产物输出到 `dist\`；版本号自动读自 `app/build.gradle`。前置依赖与签名密钥说明见 [docs/WORKFLOW.md](docs/WORKFLOW.md)。

也可用 Android Studio（Ladybug+，AGP 8.5）打开工程正常构建。仅做语法/API 校验不出包时用 `tools\build-check\verify.bat`。

## 激活与使用

1. LSPosed 管理器 → 模块 → 启用「MiFit机型伪装」→ 作用域勾选**小米运动健康** → 按提示重启手机。
2. 打开「MiFit机型伪装」App（或 LSPosed 内模块设置入口），确认顶部显示「已连接 LSPosed 服务」。
3. 选择目标机型（预设或自定义；自定义必须填 model，如 `Xiaomi Smart Band 10`）→ 保存。
4. **强停「小米运动健康」→ 重新打开 → 设备页断开并重连手环 → 进入表盘商店**查看变化。

## 真机测试与日志回流（重要）

模块带有探测日志，首次真机验证请按以下步骤采集并反馈：

```bat
adb logcat -s LSPosed-Bridge:V
```

（或在 LSPosed 管理器日志页按 tag `MifitMask` 过滤）关注：

- `hooked: ...` / `hook failed: ...` —— 各 hook 点成败；
- `probe class ...` / `probe absent: ...` —— 候选类存在性与方法签名；
- `probe BluetoothDevice.getName() = ...` —— 真实设备名观察。

把以上输出 + 目标 App 版本号反馈到 `docs/WORKLOG.md`，即可迭代固化 hook 点（流程见 docs/WORKFLOW.md）。

## 回滚

关闭总开关（或 LSPosed 停用模块）→ 强停小米运动健康 → 必要时清除其数据并重新配对手环。

## 项目文档

- [docs/WORKLOG.md](docs/WORKLOG.md) —— 作业日志（已完成/待办/结论）
- [docs/WORKFLOW.md](docs/WORKFLOW.md) —— 作业流程、版本号与备份约定
- [docs/ROADMAP.md](docs/ROADMAP.md) —— 后续计划与可优化点
- [docs/CHANGELOG.md](docs/CHANGELOG.md) —— 版本更新日志
- [docs/versions/](docs/versions/) —— 版本备份归档

## 参考

- [libxposed/api](https://github.com/libxposed/api) —— 现代 Xposed API
- [MiFitnessAdAway](https://github.com/hao1196561270/MiFitnessAdAway) —— 同目标 App 的 API 102 模块参考
- [Wearable-Debug](https://github.com/A5245/Wearable-Debug) —— 小米运动健康表盘/小程序安装调试插件
- [Gadgetbridge xiaomi.proto](https://github.com/Freeyourgadget/Gadgetbridge) —— 小米可穿戴 BLE 协议（机型上报在固件侧，故本模块走应用内伪装）
