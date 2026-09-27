# 更新日志（CHANGELOG）

> 遵循 Keep a Changelog 格式；版本号语义化（见 docs/WORKFLOW.md）。
> 每次发版必须在本文件追加条目，并归档 `docs/versions/v{版本}-{日期}.zip` 与 `.apk`。

## [1.4.0] - 2026-09-27

### Fixed（本地安装卡"已发起"）
- **根因**（日志实证）：进程判定用 `ApplicationInfo.processName`，多进程下恒为主包名 → 三进程 `executor=false` → 无人轮询执行。修复为 `onModuleLoaded` 时记录 `ModuleLoadedParam.getProcessName()`（真实进程名）。

### Added（表盘商店直取——完整闭环）
- **开源调研**：[get-mi-watchface](https://github.com/aurysian-yan/get-mi-watchface) 证实官方表盘商店有**免登录公开接口** `watch-appstore.iot.mi.com/api/watchface/prize/detail?model=<代号>&id=<数字faceId>`，响应含 `data.recommend_list[0].config_file`（.bin 下载地址），**与账号无关、任意机型代号可查**。
- 新增「表盘商店直取」：设置页输入目标机型（预设或自定义）+ 数字 faceId → 官方接口查询（显示表盘名）→ 下载 .bin → 自动进入本地安装队列（v1.3 通道）推送手环。**"用其他机型的表盘"完整闭环打通**。
- **预设代号表按官方映射全面修正**：手环 10 = `miwear.watch.o66cn`（此前 q69cn 错误）、Watch S3 = `mijia.watch.n62`、Watch S4 = `o62`、Redmi Watch 4/5 = `n65`/`o65`、8 Pro = `lchz.watch.m67`、新增 9 Active = `n69cn`；并记录命名规律（首字母=年份，数字第二位=系列）。

## [1.3.1] - 2026-09-27

### Fixed（本地安装指令通道重构）
- **真机问题**：指令经 RemotePreferences 传递在多进程下值抖动（重复触发）且结果反向同步不可靠（设置页一直显示"已发起"）；仅 hook doInstall 捕获零命中。
- **修复**：① 指令/结果通道全部改走 `FileServeProvider`（query command / insert result，进程内存 + relay 文件），不再依赖 RemotePreferences 跨进程值同步；② **补 hook `preInstall`**（商店安装真实路径，v1.3.0 仅 hook doInstall 零命中捕获）；③ 仅 `:device` 进程执行安装，其他进程仅捕获（避免三进程结果互相覆盖）；④ token 单调判定 + 防重入。

## [1.3.0] - 2026-09-27

### Added（本地表盘文件直装——绕过商店市场）
- **结论固化**：真机验证（o66tc 参数改写全部触发仍显示 9 Pro 表盘）确认商店市场由服务端账号绑定决定，参数层伪装无法改变。市场切换路线废弃，转向**本地表盘文件直装**（社区成熟路径，Wearable-Debug 同款原理）。
- 新增 `LocalInstallHooks`：hook `FaceInstallBleImpl/HuamiImpl.doInstall(path, faceId, Integer, callback)` 捕获 App 自身安装时的安装器实例与参数形态；目标进程轮询 RemotePreferences 安装指令。
- 设置页新增「本地表盘安装」区：faceId 输入 + 文件选择器（.bin）+ 结果轮询显示；文件经自定义 `FileServeProvider`（authority `io.github.mifitmask.files`）中继到目标进程，复制到其 WatchFace 目录后 `doInstall` 推送，动态代理 `FaceInstallPushCallback`（接口）回报进度/结果。
- 使用前置：**先在商店正常安装任意一个表盘**（捕获安装器实例），之后即可重复安装本地文件。
- 表盘文件来源：米坛社区（BandBBS）等有各机型表盘 .bin 资源；跨机型安装的兼容性由手环固件判定。

## [1.2.2] - 2026-09-27

### Changed
- 全局出站侦察：hook `Request.Builder.url`（覆盖所有 OkHttp client，含早期构建实例），用于判定商店数据来源（REQ 行）。

## [1.2.1] - 2026-09-27

### Added
- `FaceParam` 覆写：请求方法参数中的 `FaceParam` 实例（字段 `model`/`id_list`，无 getter）在调用点反射直改 `model` 字段（观察 `FACE-PARAM` / 改写 `FACE-PARAM-REWRITE`），补上 `checkFaceUnavailable(FaceParam,...)` 这类对象参数通道。

### Verified
- v1.2.0 真机日志（关键突破）：`FACE-REQ getFaceEntranceV4(miwear.watch.n67cn, false, 0, CN, …)` —— **表盘商店入口请求的第一参数即已连接设备代号**（`n67cn` 再次实锤）；`FACE-REWRITE getFaceEntranceV4(miwear.watch.q69cn, …)` / `getFaceDetail(miwear.watch.q69cn, …)` —— **替换后进入请求流程，无异常**。应用层改写链路完整打通。

## [1.2.0] - 2026-09-27

### Added（架构转向：应用层请求参数改写）
- **网络层方案废弃**：真机侦察（REQ 日志）证实小米运动健康全部业务请求走加密端点（`data=` 密文 + `signature`），参数在密文中且有签名校验——网络层改写不可行，相关开关标注废弃。
- **新增 `FaceRequestHooks`（v1.2 主功能）**：hook `FaceApiRequestV2` 全部非静态方法（表盘域请求封装，dexdump 实证：getFaceEntranceV2/V4、requestDownload、requestFaceCapability、getPackageTasks 等 12 个 suspend 方法），在**加密前的明文参数**处做值匹配替换：
  - 参数 String ≈ 当前机型代号（支持省略前缀）→ 替换为目标代号；
  - 参数 Number == 当前 productId → 替换为目标 productId（类型对齐）；
  - 加密与签名基于改后明文，服务器无法区分；本地身份层与连接零接触。
- 新增独立开关「改写表盘请求参数（推荐开启）」默认开；观察日志 `FACE-REQ`（参数全貌）/`FACE-REWRITE`（替换明细）。
- 网络侦察升级：拦截器记录**全部**去重请求 URL（`REQ` 行），本轮即据此确认加密端点架构。

## [1.1.7] - 2026-09-27

### Fixed（真机回归：设备页清空且关闭模块不恢复）
- **回归现象**：v1.1.6 选中目标机型后设备页为空（显示"添加设备"），关闭模块/停用也不恢复。
- **根因链**：① 产品目录里 `model=n67cn` 的条目就是已连接手环的档案（bltNamePrefix 与 model 同对象），目录层改写让它对不上 → 设备页清空；② **改写值被 App 写入本地库形成持久污染**——hook 是运行时的，App 持久化的是它"看到的"值，因此撤掉 hook 也无法自愈（上一轮"豁免"因该会话中 `getBltNamePrefix` 未被调用而失效）。
- **修复**：新增「本地机型改写（危险，默认关）」总闸——Product 目录 + Device 标识的全部改写默认禁用，**默认配置 = 纯观察模式**（本地数据零接触）；`getBleName` 一并纳入；侦察扩展为记录全部去重请求 URL（`REQ` 行），确保下轮拿到表盘商店真实参数键名。
- **恢复指引**：清除小米运动健康数据（本地未同步数据会丢失）→ 重新登录 → 重新添加手环。

## [1.1.6] - 2026-09-27

### Fixed（真机回归：目标 App 网络请求崩溃）
- **回归现象**：v1.1.5 启用后小米运动健康每个网络请求崩溃（App 闪退/不可用）。
- **根因**（真机堆栈实证）：`NullPointerException: RealInterceptorChain.proceed, parameter request` —— v1.1.5 将 OkHttp 拦截器注入放宽到总开关后，拦截器首次真实处理请求；无改写需求时代码以 `proceed(null)` 放行，触发 OkHttp 参数非空校验崩溃。此前默认关闭从未执行到该路径，属潜伏缺陷。
- **修复**：无改写需求时以原 request 继续（`proceed(request)`）；request 反射不可得时抛出异常交由 OkHttp 将单次请求标记失败（不崩进程）。

## [1.1.5] - 2026-09-26

### Added
- 设备条目豁免：Product 目录改写跳过「已连接设备」条目（运行时按 bltNamePrefix 识别），修复 v1.1.4 目录改写导致设备页清空的回归。
- 网络侦察模式：OkHttp 拦截器在总开关开启时即注入，对命中商店关键词的请求记录完整 URL（STORE-URL 日志，同 URL 去重），用于确认表盘商店真实参数键名；参数改写仍由实验开关控制。

## [1.1.4] - 2026-09-26

### Fixed（真机回归：连接被破坏）
- **回归现象**：v1.1.3 在真机上导致小米运动健康无法连接手环（添加设备不可见），总开关关闭亦不恢复。
- **根因 1（日志实证）**：`Device.getModel` 真实值为 `miwear.watch.n67cn`（手环 9 Pro，用户指正后被观察日志证实），值匹配改写把它改成目标机型代号 → App 侧设备身份与手环实体不符 → 连接/添加失败。
- **根因 2**：蓝牙名改写（`BluetoothDevice.getName`）把真机广播名（含 "Band"）改掉 → App 的 `bltNamePrefix` 匹配不到 → 添加列表里看不到手环。

### Changed（安全化）
- 「改写蓝牙设备名」「改写已连接设备标识」拆为**独立危险开关，默认关闭**（红字警示）；默认只保留无害的产品目录层覆写与全量观察。`bltNamePrefix` 改写同样纳入蓝牙名危险开关约束。
- 手环 9 Pro 代号确认为 `miwear.watch.n67cn`（用户指正 + 日志实证），预设表已更新。

### Fixed（UI）
- 设置页所有输入框增加明确标签行（此前仅有浅色 hint，用户无法分辨哪个输入框对应哪个参数）；危险开关标题红色。

### Recovery
- 受影响用户恢复步骤：LSPosed 停用模块 → 重启手机 → 必要时清除小米运动健康数据并重新配对。

## [1.1.3] - 2026-09-26

### Added
- 终局观察点：hook `bean.Device`（已连接设备档案）的 `getModel / getProductId`——App 运行时真正读取的设备标识来源，直接验证当前机型代号（用户反馈手环 9 Pro 代号可能为 `n67cn`，与产品目录代号 `o66tc` 属不同数据层，两者可并存）。
- 值匹配增强：`modelMatches` 支持省略 `miwear.watch.` 等前缀的短代号（填 `n67cn` 可匹配 `miwear.watch.n67cn`）。
- 预设表迁移为内部代号体系（依据真机观察 + 用户指正）：手环 9 Pro = `miwear.watch.n67cn`、手环 9 = `n66cn`、8 Pro = `m66nfc`、Watch S3 = `o61lte`、Watch S4 = `p62`、Redmi Watch 5 = `q65acn`、手环 10 = `q69cn`（待校准）；明确 preset 的 model 不再使用蓝牙显示名。

### Verified
- 真机日志：值匹配 REWRITE 首次触发（`o66tc -> 目标机型`），证明 Product getModel 覆写链路在真实使用场景被调用；本轮产品目录观察扩至 100+ 款（含 `p67cn/p67tc`、`n69cn`、`q66` 系等 2026 新款）。

## [1.1.2] - 2026-09-26

### Changed
- `OBSERVE` 行新增对象哈希（`obj=`），用于把 `getProductId`/`getModel` 观察值与设备对象关联。
- `dumpProductFields` 异常可见化（此前字段反射失败被静默吞掉）。

## [1.1.1] - 2026-09-26

### Changed
- `LayerProductHooks` 观察增强：`getBltNamePrefix` 观察模式新增 `OBSERVE-DEVICE` 日志，意图一次反射读出同一 Product 对象的 model/productId/bltNamePrefix（真机验证该反射读取在部分环境下静默失败，字段可见性修复移至 1.1.2）。

### Verified
- 真机日志确认 v1.1.0 主 hook 点在三个进程全部生效；观察模式产出 App 完整产品代号表（手环 9 家族 = `miwear.watch.o66cn/o66lj/o66tc`，Watch S3 = `o61lte` 等 44 款）；`getProductId` 观察捕获产品目录数值（与具体产品的对象级关联移至 1.1.2）。

## [1.1.0] - 2026-09-26

### Added
- 第 1 层强化 `LayerProductHooks`（新主 hook 点，来源：真机 APK dexdump 实证，不再依赖猜测类名）：
  - `com.xiaomi.fitness.device.manager.bean.Product` 的 `getModel / getProductId / getBltNamePrefix`
  - `com.xiaomi.fitness.device.manager.bean.DeviceInfo` 的 `getBleName`
- **值匹配工作模式**：设置页新增「当前机型标识」（model + productId）两栏；getter 返回值与当前机型标识相等时才替换为目标机型，未填 = 观察模式（只记录不改写），避免 v1.0 的盲改误伤。
- 观察模式日志（`OBSERVE` 前缀）用于真机日志回流，采集 App 内真实产品代号表。

### Fixed
- `BluetoothDevice.getName` 覆写误伤：此前把周边蓝牙设备名（家电/耳机等）一并改写，现仅匹配形似小米穿戴设备的名字（`looksLikeWearable` 白名单）。
- `ProbeHooks` 蓝牙名观察刷屏：同值去重，仅记录形似穿戴设备的名字。

### Note
- 目标机型的 productId/deviceSource 数值映射仍待观察数据回填（`DeviceProfiles` 中暂空）。

## [1.0.3] - 2026-09-26

### Fixed
- 设置页仍「未连接 LSPosed 服务」的第二个缺陷：`io.github.libxposed:service` 存在 runtime 依赖 `io.github.libxposed:interface`（含 `IXposedService`/`IXposedScopeCallback`/`IHotReloadCallback` 等 AIDL Binder 类），Gradle 经传递依赖自动打包，手工流水线漏掉——即使框架推送 binder 成功，`XposedServiceHelper.onBinderReceived` 也会因缺类抛 NoClassDefFoundError 且被内部 catch 静默吞掉。已把 interface 库一并 dex 进 APK（dexdump 复核：IXposedService 等全部定义在 dex，api 包维持零定义）。
- `app/build.gradle` 显式声明 `implementation 'io.github.libxposed:interface:102.0.0'`，使依赖清单与手工流水线一致；verify.bat/build-apk.ps1 同步更新。

### Note
- 框架侧推送为「uid 首次活跃时一次」：覆盖安装（杀进程）或重启手机都会触发重新推送。若安装后仍显示未连接，先强杀本 App 再打开一次，无效则重启手机。

## [1.0.2] - 2026-09-26

### Fixed
- 设置页「未连接 LSPosed 服务」、保存按钮永久禁用：手工流水线构建缺少 manifest merger，service AAR 内置的 `XposedProvider` 声明（authority `${applicationId}.XposedService`）未被合并进 APK，LSPosed 框架找不到推送服务 binder 的入口。已在 AndroidManifest 显式声明该 provider。
- 设置页副标题版本号由硬编码改为运行时读取 PackageManager。

### Note
- 未连接时保存按钮禁用是既有设计（RemotePreferences 只能经框架服务写），并非本次缺陷。

## [1.0.1] - 2026-09-26

### Fixed
- 模块 App 启动闪退（点击图标即崩）：手工打包流水线漏把 `io.github.libxposed:service` 打进 APK，导致 `MaskApp` 加载时 `ClassNotFoundException: XposedServiceHelper$OnServiceListener`。构建脚本（build-apk.ps1）d8 步骤改为将 service 库一并 dex 进 classes.dex（api 库维持 compileOnly 不打包，与 Gradle `implementation/compileOnly` 语义等价）。

### Verified
- 目标进程 hook 正常：LSPosed 2.2.0 日志确认 `com.mi.health:device` 进程 module loaded（api=102），layer1/layer2 hook 安装成功无异常。
- 附带发现：设备档案候选类在当前目标 App 版本全部未命中（probe absent），待 v1.1 用主进程探测日志固化（不阻塞本修复）。

## [1.0.0] - 2026-09-25

### Added
- 首个版本。基于 libxposed API 102 的 LSPosed 模块，目标：小米运动健康（国内 `com.mi.health` / 国际 `com.xiaomi.wearable`）。
- 入口骨架：`onModuleLoaded / onPackageLoaded / onPackageReady` 生命周期，非目标包 `detach()`，`tryHook` 失败隔离。
- 第 1 层·本地设备档案伪装（默认开）：`BluetoothDevice.getName()` 覆写 + 设备档案候选类零参 getter 多候选覆写（model / 设备名 / 数值标识）。
- 第 2 层·表盘商店请求参数重写（实验，默认关）：hook `OkHttpClient.Builder.build()` 注入动态代理拦截器，改写 watchface 请求的 query/JSON 机型字段。
- 探测日志 `ProbeHooks`：候选类存在性与方法签名、蓝牙名、表盘域 getter 取值观察（调试开关控制）。
- 设置页（原生 UI）：总开关 / 机型预设 8 款 + 自定义 / 网络重写开关 / 调试日志开关，经 RemotePreferences 跨进程同步。
- 机型预设：小米手环 8 / 8 Pro / 9 / 9 Pro / 10、Watch S3 / Watch S4、Redmi Watch 5（model 取社区通用蓝牙名；数值字段待抓包后补全）。
- 文档体系：WORKLOG（作业日志）/ WORKFLOW（作业流程与版本约定）/ ROADMAP（后续计划）/ CHANGELOG / README；`docs/versions/` 版本备份目录（源码 zip + APK 双件套）。
- 构建产物：`dist/MiFitDeviceMask-v1.0.0.apk`（v1+v2 测试签名，可直接安装）；免 Gradle 一键构建脚本 `tools/build-apk.ps1`。

### Known Issues
- 机型档案精确混淆类名待真机探测日志回流后固化（v1.0.0 为多候选策略，未验证）。
- `productId / deviceSource` 数值映射未确认，预设中暂为空（仅覆写 model 字符串）。
- 第 2 层网络重写参数键名为社区常见命名集合，未经实机验证，故默认关闭。
