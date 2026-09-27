# 作业日志（WORKLOG）

> 每次迭代追加日期小节：已完成 / 待办 / 结论与依据 / 下一步。
> 记录粒度以"另一位开发者能凭此接手"为准。

---

## 2026-09-25 · 调研阶段 + v1.0.0 工程搭建

### 已完成

**调研（结论为后续迭代的依据）**
- [x] libxposed API 102 用法确认（Maven `io.github.libxposed:api:102.0.0`，AAR 发布）：
  - 入口类继承 `XposedModule`；生命周期 `onModuleLoaded / onPackageLoaded / onPackageReady`；
  - hook 写法 `hook(Method).intercept(chain -> ...)`，`Chain` 提供 `proceed()/proceed(args)/getArg(i)/getThisObject()/getArgs()`；
  - 非目标包 `detach()` 停止后续回调；设置同步 `getRemotePreferences(group)` 返回 `SharedPreferences`（目标进程内只读）；
  - 模块 App 侧经 `io.github.libxposed:service:102.0.0` 的 `XposedServiceHelper` + `XposedService.getRemotePreferences()` 写入。
- [x] 模块声明格式确认（新式，不用 assets/xposed_init）：
  - `META-INF/xposed/module.prop`（minApiVersion=102 / targetApiVersion=102 / staticScope=true）；
  - `META-INF/xposed/java_init.list`（入口类全名）；`META-INF/xposed/scope.list`（作用域包名）；
  - Manifest 无需 xposedmodule meta-data，保留 `xposeddescription` 与 `de.robv.android.xposed.category.MODULE_SETTINGS`。
- [x] 参考实现核对：MiFitnessAdAway（API 102 + com.mi.health/com.xiaomi.wearable 双包名）、Wearable-Debug（表盘域真实类名）。
- [x] 已验证的目标 App 内真实类名（可放心引用）：
  - `com.xiaomi.fitness.watch.face.export.FaceHelperImpl`（表盘红点等）；
  - `com.xiaomi.fitness.watch.face.install.FaceInstallBleImpl / FaceInstallHuamiImpl`，方法 `doInstall(String,String,Integer,callback)` / `preInstall(String,String,long,long,boolean,String,String,Integer,Function3)`；
  - RN 宿主 `com.xiaomi.yrn.controller.ui.YRNCFragment`（RN 页面渲染入口）。
- [x] BLE 协议层结论（Gadgetbridge xiaomi.proto）：机型在 `System.DeviceInfo.model` 由固件上报，App 侧无法改协议栈 → 放弃协议层伪装，采用应用内分层伪装。
- [x] 表盘市场按"已连接设备机型"返回（社区抓包共识 + Wearable-Debug/HookUtils 模块佐证）。

**工程（v1.0.0）**
- [x] Gradle 骨架（AGP 8.5.2 / compileSdk 35 / minSdk 26 / Java 17 / minify 关闭——入口类名不能被混淆）。
- [x] 入口 `MaskModule`（生命周期 + `tryHook` 失败隔离 + profile 缓存）。
- [x] 设置键 `Prefs`、机型预设表 `DeviceProfiles`（8 预设 + 自定义，model 字段来自社区通用蓝牙名）。
- [x] 第 1 层 `hooks/DeviceIdentityHooks`：`BluetoothDevice.getName()` + 设备档案候选类零参 getter 多候选覆写（model/name/数值标识）。
- [x] 第 2 层 `hooks/StoreParamHooks`：OkHttp `Builder.build()` 注入动态代理拦截器，改写 watchface 请求 query/JSON 字段（实验，默认关）。
- [x] 探测 `hooks/ProbeHooks`：候选类存在性/方法签名、蓝牙名与表盘域 getter 取值观察，输出到 Xposed 日志。
- [x] 设置页 `SettingsActivity`（原生程序化 UI）+ `MaskApp`（XposedServiceHelper 绑定）。
- [x] 本机编译校验链路：tools/build-check（JDK 17 + android-35 android.jar + api/service/annotation/kotlin jars，javac 校验）。
- [x] **v1.0.0 APK 构建并签名**（免 Gradle 流水线：aapt2 link → d8 → 组装 → zipalign → apksigner v1+v2），产物 `dist\MiFitDeviceMask-v1.0.0.apk`（24.6KB，badging/xmltree 已核验）；流水线固化为 `tools\build-apk.ps1`，签名密钥 `tools\signing\mifitmask.p12`（复用同密钥保证可覆盖升级）。
- [x] 发版双件套约定落地：`docs/versions/` 同时归档源码 zip 与 APK。
- [x] 文档五件套 + README。

### 待办（按优先级）
- [ ] 真机安装 `dist\MiFitDeviceMask-v1.0.0.apk` 验证：LSPosed 激活 → 探测日志回流 → 固化有效 hook 点（改 DeviceIdentityHooks 候选表）。
- [ ] 抓包确认表盘商店接口的机型参数名（deviceSource/productCode/productId 等真实键名与取值），补充 DeviceProfiles 数值字段。

### 结论与依据
- 分层方案成立：本地档案层是主攻方向（社区模块走通）；网络层参数键名未证实前保持默认关闭。
- 类名漂移是最大风险 → 多候选 + 探测日志 + 版本固化（WORKLOG 记录目标 App 版本号）。

### 下一步
见 docs/ROADMAP.md v1.1.0。

---

## 2026-09-26 · 真机日志回流（v1.0.0）+ v1.0.1 闪退修复

### 已完成
- [x] 分析用户提供的 LSPosed 日志（LSPosed 2.2.0，两份 2026-09-25 23:50/23:59 导出）。
- [x] 定位模块 App 闪退根因并发布 v1.0.1（详见 CHANGELOG）：手工流水线漏把 `io.github.libxposed:service` 打进 APK → `MaskApp` 加载 `XposedServiceHelper$OnServiceListener` 失败 → 启动即崩。构建脚本已修复（d8 输入加入 service-classes.jar）。
- [x] dexdump 复核 v1.0.1 dex 类定义表：service 包 23 类已定义、api 包 0 定义（api 由框架在被 hook 进程提供，不打包）。

### 结论与依据
- **目标进程 hook 链路正常**：modules.log 中 `(com.mi.health:device)` 进程 `module loaded, api=102, framework=LSPosed 2.2.0`，layer1/layer2 均 `hooked:` 无 fail；装包问题只影响模块 App 自身。
- **设备档案候选类全部未命中**（probe absent）：本次日志只有 `:device` 子进程记录，且候选类名是猜测集合 → 主进程探测日志需待 v1.0.1 装好后重新回流。
- full.log 中另一条 FATAL（23:23:38）属 `com.heytap.cloud`（系统云服务），与本模块无关。

### 待办
- [ ] 真机安装 v1.0.1：确认模块 App 能打开、能保存机型（连带验证 RemotePreferences 写入链路）。
- [ ] 开调试日志抓**主进程**（com.mi.health 无 `:` 后缀）probe 输出，固化设备档案 hook 点（v1.1.0 主体工作）。

---

---

## 2026-09-26（晚） · v1.0.2 服务接入点修复

### 已完成
- [x] v1.0.1 真机验证：App 不再闪退（v1.0.1 修复生效）；但设置页始终「未连接 LSPosed 服务」。
- [x] 定位根因：读 `io.github.libxposed:service:102.0.0` 源码确认服务绑定机制——`XposedServiceHelper.onBinderReceived` 由模块 App 自身 manifest 声明的 `XposedProvider`（ContentProvider）回调，LSPosed 框架经 `call(authority, SEND_BINDER)` 推送 binder。Gradle 构建时该声明由 service AAR 内置 manifest 自动合并，手工流水线无 merger → 声明丢失 → 永远连不上。
- [x] v1.0.2 修复：AndroidManifest 显式声明 `io.github.libxposed.service.XposedProvider`（authority=`io.github.mifitmask.XposedService`，exported=true，与 AAR 内置声明一致）；设置页副标题版本号改为运行时读取。已用 aapt2 dump xmltree 复核 provider 编译进最终 manifest。

### 结论与依据
- service AAR 内置 manifest 的 provider 声明是「看不见的必要条件」：源码层看不到（merger 干的），手工构建必须显式补齐。**今后若引入其他带内置 manifest 的 AAR，须逐一核对其内置声明。**
- 「未连接时保存按钮禁用」为设计行为（RemotePreferences 只能经框架服务写），非缺陷。

### 待办
- [ ] 真机安装 v1.0.2 验证：设置页应显示「已连接 LSPosed 服务」→ 保存机型 → 强停目标 App 重开验证。
- [ ] 主进程探测日志回流（v1.1.0 hook 点固化，同上）。

---

---

## 2026-09-26（夜） · v1.0.3 服务依赖补全

### 已完成
- [x] v1.0.2 真机验证：版本号动态显示正常（证明新版已装），但服务仍未连接。
- [x] 拉取 LSPosed 官方源码梳理推送链路：daemon 经 UidObserver（ACTIVE/IDLE/非cached）触发 `LSPModuleService.uidStarts(uid)` → `getModule(uid)` 且 `!module.file.legacy` → `provider.call(authority=pkg+".XposedService", SEND_BINDER)` 推 binder；拿不到 provider 只记 debug 日志，且 uid 去重（失败后需 uid 死亡重开才重试）。
- [x] 发现第二个打包缺陷：`service:102.0.0` 的 pom 声明 runtime 依赖 `interface:102.0.0`（IXposedService 等 AIDL 类所在）——Gradle 传递依赖自动打包，手工流水线漏掉 → 推送来了也会在 `onBinderReceived` 的 `IXposedService.Stub.asInterface` 处 NoClassDefFoundError 并被 catch 静默吞掉。
- [x] v1.0.3 修复：interface-classes.jar 入 cache/构建脚本/verify.bat，`app/build.gradle` 显式声明依赖；dexdump 复核 IXposedService$Stub 等全部定义在 dex。

### 结论与依据
- 手工流水线绕过了 Gradle 的依赖解析与 manifest merger，两处「隐形必要条件」（AAR 内置 manifest、传递依赖）都已踩坑。**约定：新增任何依赖前，先查其 pom 依赖与内置 manifest，并在 build-apk.ps1 的 bundle 清单中显式补齐。**
- 用户环境补充信息：LSPosed 管理器包名 org.lsposed.manager（官方），root 方案 KernelSU，厂商 OPPO（ColorOS）。

### 待办
- [ ] 真机安装 v1.0.3：应显示「已连接」；若未连接，强杀 App 重开一次（触发 uid 重新推送），仍不行则重启手机。
- [ ] 连接成功后保存机型 → 验证表盘商店变化 → 主进程探测日志回流（v1.1.0）。

---

## 2026-09-26（夜 2）· 真机自动化打通 + v1.1.0/1.1.1 观察模式落地

### 已完成
- [x] **真机 USB 半自动验证打通**（OPPO PLK110 + LSPosed 2.2.0）：v1.0.3 安装后设置页显示「● 已连接 LSPosed 服务」，保存/同步链路全部工作——**v1.0.2/v1.0.3 的修复经真机验证成立**。
- [x] 模块在 com.mi.health 主进程及 :device/:pushservice 子进程全部注入；`BluetoothDevice.getName` 伪装实时生效（日志可见 mask=Xiaomi Smart Band 9）。
- [x] 用户手机 APK 拉取 + dexdump 静态分析（106581 个类）：定位**真实类**——表盘请求 `com.xiaomi.fitness.watch.face.request.FaceApiRequestV2`（getFaceEntranceV2/V4/requestDownload）、机型档案 `device.manager.bean.Product`（字段 model/productId/bltNamePrefix）、`bean.DeviceInfo`（bleName）、`bean.Device`。
- [x] v1.1.0：新增 `LayerProductHooks` 值匹配 hook（详见 CHANGELOG）；修蓝牙名误伤；probe 降噪。
- [x] v1.1.0 观察模式真机数据回流：App 内 44 款产品代号表 + `getBltNamePrefix()="Xiaomi Smart Band 9 Pro"`（确认用户设备为手环 9 Pro）+ 产品目录 productId 数值集合。
- [x] v1.1.1：`OBSERVE-DEVICE` 一次性输出设备 Product 三字段（真机上字段反射读取静默失败，v1.1.2 修复）。
- [x] 归档补齐：v1.1.0/v1.1.1 双件套入 docs/versions（发现并修复打包规则缺陷：staging 未排除 docs/versions 导致 zip 递归膨胀，已写入 WORKFLOW 注意事项）。

### 结论与依据
- **值匹配模式优于盲改**：Product 表是"产品目录"（44 款），盲改 getModel 会把整个目录都改掉；按"当前机型标识"匹配只改用户设备那条。
- 真机观察到的手环 9 家族代号：`o66cn`（9 NFC）/`o66lj`/`o66tc`（9 Pro 待确认）；Watch S3=`o61lte`；Watch S4 疑似 `p62`；Redmi Watch 5 疑似 `q65acn`。
- 用户设备 productId 尚未与对象级关联（App 对目录产品缓存读、未走 getter；需对象 hash 关联，见 v1.1.2）。
- 环境教训：adb 在手机拔线后陷入系统级僵死（Windows USB 残留节点），本机 adb 需重启电脑恢复；期间改用 QQ 传文件 + 用户手机操作推进。

### 待办
- [x] 用户填入「当前机型 model」（先试 `miwear.watch.o66tc`，若商店无反应再试 `o66cn`）+ 保存 → 强停重开小米运动健康 → 看表盘商店是否切换市场。
- [x] v1.1.2：修复 Product 字段反射静默失败（记录异常详情）；getProductId/getModel OBSERVE 加对象 hash，与 bltNamePrefix 关联锁定 productId。
- [ ] 表盘市场切换确认后，评估是否需要 productId/deviceSource 数值覆写（若仅 model 字符串不足）。

## 2026-09-26（夜 3）· v1.1.3 连接回归 + v1.1.4 安全化

### 已完成
- [x] **代号实证**：真机日志 `REWRITE Device.getModel: miwear.watch.n67cn -> miwear.watch.n66cn` —— 手环 9 Pro 的连接设备代号确认为 **n67cn**（用户指正正确；o66tc 是产品目录层的另一个代号）。
- [x] **回归根因定位**：Device.getModel 改写 → 设备身份不匹配 → 无法连接/添加；蓝牙名改写 → bltNamePrefix 匹配失败 → 添加列表不可见。
- [x] v1.1.4 安全化：蓝牙名/已连接设备标识两类改写拆为独立危险开关（默认关，红字警示），默认只保留产品目录层覆写 + 全量观察；bltNamePrefix 纳入蓝牙名开关约束；设置页输入框全部加标签行（用户反馈无法分辨参数）。

### 结论与依据
- 「已连接设备」(`bean.Device`) 的标识改写天然危险：App↔手环的匹配/鉴权依赖真实值，改它必然影响连接；**商店伪装的正确切入点是产品目录层（Product）与网络参数层**。
- 用户观察力关键：n67cn 的指正把 Device 层代号一次锁定，省掉多轮观察。

### 待办
- [ ] 用户恢复连接（停用模块→重启→必要时清数据重配对）后装 v1.1.4 验证：默认配置下连接恢复正常。
- [ ] 商店伪装验证：Product 层改写（当前机型 n67cn → 目标代号）+ 商店请求观察，确认市场是否切换；不足则开危险开关或上网络层。

---

<!-- 后续迭代在此追加，例如：
---

## 2026-09-26（夜 4）· v1.1.5 设备条目豁免 + 网络侦察
- [x] 定位"设备页清空且关闭模块不恢复"根因：目录中 model=n67cn 条目 = 用户手环档案（bltNamePrefix 同对象），目录改写破坏 Device↔Product 关联；**改写值被 App 写入本地库 → 持久污染，hook 撤除不自愈**。上轮"豁免"失效原因：该会话 getBltNamePrefix 未被调用，sDevicePrefix 未记录。
- [x] v1.1.7：新增「本地机型改写（危险，默认关）」总闸统一管 Product/Device/bltNamePrefix（蓝牙名独立开关不变）；**默认配置 = 纯观察，本地数据零接触**。侦察升级：全部去重请求 URL（REQ 行）+ 商店命中（STORE-URL 行）。

### 结论与依据
- **本地身份层（Product 目录设备条目 / Device）在任何模式下都不可改**：它与连接、设备页渲染、本地持久化三方耦合。市场伪装唯一安全路径 = 网络请求层（REQ/STORE-URL 侦察 → v1.2 精准重写）。
- 模块全部行为为运行时 hook，但 **App 会把 hook 呈现的值持久化**——"hook 只读所以无害"的假设不成立，已写入 WORKFLOW 纪律。

### 待办
- [ ] 用户：清除小米运动健康数据 → 重配对 → 装 v1.1.7（默认纯观察）→ 确认设备页/连接正常。
- [ ] 用户逛商店 → 导出日志 → 分析 REQ/STORE-URL，锁定表盘商店参数键名（deviceSource/productCode 真名）→ v1.2 网络层精准重写。

---

## 2026-09-27 · v1.1.6 拦截器 NPE 修复

### 已完成
- [x] 定位 v1.1.5 启用后目标 App 网络请求崩溃根因（真机堆栈）：`proceed(null)` 触发 OkHttp 参数非空校验 NPE——v1.1.5 拦截器注入放宽到总开关后该路径首次被真实执行（此前默认关属潜伏缺陷）。
- [x] v1.1.6 修复：无改写需求时 `proceed(原 request)`；request 反射不可得时抛异常让 OkHttp 将单次请求标记失败（不崩进程）。
- [x] 归档 v1.1.6 双件套 + CHANGELOG。

### 结论与依据
- **教训（写入 WORKFLOW）**：放宽任何 hook 的执行条件时，其下游代码路径会被首次真实执行——发布前必须让该路径在默认配置下跑过至少一次（本例：装上后逛一次商店即可暴露）。
- 顺带确认：本机 full.log 中两条 FATAL（com.heytap.cloud）与本模块无关。

### 待办
- [ ] 用户装 v1.1.6：确认小米运动健康不再闪退、设备页正常、逛商店后导出日志 → 分析 STORE-URL 真实参数键名。

---

## 2026-09-27（下午）· v1.1.7 本地改写总闸 + 全量侦察

### 已完成
- [x] 定位"设备页清空且关闭模块不恢复"根因：目录中 model=n67cn 条目 = 用户手环档案（bltNamePrefix 同对象），目录改写破坏 Device↔Product 关联；**改写值被 App 写入本地库 → 持久污染，hook 撤除不自愈**。上轮豁免失效：该会话 getBltNamePrefix 未被调用。
- [x] v1.1.7：新增「本地机型改写（危险，默认关）」总闸统一管 Product/Device/bltNamePrefix（蓝牙名独立开关）；**默认配置 = 纯观察**。侦察升级：全部去重请求 URL（REQ）+ 商店命中（STORE-URL）。

### 结论与依据
- 本地身份层与连接/设备页/持久化三方耦合，任何模式下都不可改；市场伪装唯一安全路径 = 网络请求层。

### 待办
- [ ] 用户清数据重配对 → 装 v1.1.7（纯观察）确认设备页正常 → 逛商店导出 REQ/STORE-URL 日志。

---
## 2026-09-27（晚）· v1.2.0 应用层请求参数改写（架构转向）

### 已完成
- [x] 分析 v1.1.7 全量侦察日志（637 条去重请求）：**小米运动健康全部业务请求走 `hlth.io.mi.com` 加密端点**（`data=` RC4 密文 + `signature`）——网络层改写方案确认不可行（参数在密文中，改了破签名），正式废弃。
- [x] 转向应用层：dexdump `FaceApiRequestV2`（表盘域请求封装）——12 个 suspend 方法（getFaceEntranceV2/V4、requestDownload、requestFaceCapability、getPackageTasks、requestLicense 等），参数即加密前明文业务参数。
- [x] v1.2.0 `FaceRequestHooks`：hook 全部非静态方法，观察（`FACE-REQ` 参数全貌）+ 值匹配改写（String ≈ 当前代号 → 目标代号；Number == 当前 productId → 目标 productId，类型对齐）——**无需知道键名**，替换发生在加密与签名之前，服务器不可区分。本地身份层零接触。
- [x] 独立开关 `rewrite_face_request` 默认开；网络层开关标注废弃保留兼容。归档 v1.2.0 双件套。

### 结论与依据
- 用户手环 9 Pro 当前代号 `n67cn`（Device.getModel 日志实证）；本轮清数据重配对前 Product/Device getter 零调用（缓存/未绑定），productId 数值待 FACE-REQ 观察或重配对后回流。
- 加密端点架构下，**应用层明文参数 hook 是唯一可行伪装路径**（本地身份层因连接耦合与持久化污染已废弃）。

### 待办
- [ ] 用户装 v1.2.0（当前机型填 n67cn，目标选手环 10）→ 逛表盘商店 → 导出日志：`FACE-REQ` 给出参数全貌（productId/deviceSource 明文值），`FACE-REWRITE` 确认替换触发。
- [ ] 商店市场切换验收；若参数中缺机型标识，再扩 hook 面（FaceApiRequest / FaceHelperImpl 链路）。

---

## 2026-09-27（夜）· v1.3.0 本地表盘直装（方向定稿）

### 已完成
- [x] o66tc 判定实验闭环：参数改写全部触发（FACE-REWRITE getFaceEntranceV4/getFaceDetail/FACE-PARAM ×6）仍显示 9 Pro 表盘 → **结论：商店市场由服务端账号绑定决定，参数层伪装不可行**（本地身份层与网络层此前也已排除）。三条路线验证完毕，方向定稿为**本地表盘文件直装**。
- [x] 技术可行性确认（dexdump）：`FaceInstallPushCallback` 为纯接口（onStart/onProgress/onFinish）可动态代理；`doInstall(String path, String faceId, Integer, callback)` 参数明确；`FaceInstallBleImpl` 为抽象类 → 实例来源 = hook 捕获 App 自身安装时的 thisObject。
- [x] v1.3.0 实现：`LocalInstallHooks`（doInstall 捕获 + RemotePreferences 指令轮询 + Proxy callback）+ `FileServeProvider`（模块 App 中继文件，目标进程经 content:// 读取）+ 设置页「本地表盘安装」区（faceId 输入/文件选择器/结果轮询）。
- [x] 归档 v1.3.0 双件套；ROADMAP 按验证结论重写（废弃路线明确标注不再重试）。

### 结论与依据
- 商店市场切换路线（参数/网络/本地身份三层）全部验证不通——服务端权威。
- **"用其他机型的表盘"的实用解 = 本地直装**：社区（BandBBS）有全机型表盘 .bin 资源；手环固件对跨机型文件有校验（同世代大概率兼容，具体以手环判定为准）。
- 使用前置（捕获安装器实例）：先在商店正常安装任意一个表盘。

### 待办
- [x] 用户装 v1.3.0：①商店正常装一个表盘（捕获实例，日志 INSTALL-CAPTURE）②设置页用社区 .bin 文件走本地安装 → 观察结果（INSTALL-RESULT）。
- [x] 真机结果分析：管道通（指令到达/结果写回）但 **INSTALL-CAPTURE 零命中**（商店安装走 preInstall 路径，v1.3.0 仅 hook doInstall）+ RemotePreferences 多进程 token 抖动/结果反向同步不可靠。
- [x] v1.3.1：指令/结果通道改走 FileServeProvider（query command / insert result）；补 hook preInstall；仅 :device 执行；token 单调 + 防重入。
- [ ] 用户装 v1.3.1 重测：①商店正常装一个表盘（INSTALL-CAPTURE preInstall 路径捕获）②本地 .bin 安装 → INSTALL-RESULT。

## 2026-09-27（夜 2）· v1.4.0 商店直取闭环 + 进程判定修复

### 已完成
- [x] 定位本地安装卡"已发起"根因（日志实证）：进程判定用 `ApplicationInfo.processName`（多进程下恒为主包名）→ 三进程 executor=false → 无人轮询。修复：onModuleLoaded 记录真实进程名。
- [x] **开源调研命中**：get-mi-watchface（aurysian-yan）证实官方表盘商店**免登录公开接口** `watch-appstore.iot.mi.com/api/watchface/prize/detail?model=<代号>&id=<数字faceId>`，响应 `data.recommend_list[0].config_file` = .bin 下载地址——**与账号无关，任意机型可查**（"获取其他机型表盘"的正解）。
- [x] **权威机型代号映射表**（来自该项目 README）：手环 8=m66cn、8 Pro=lchz.watch.m67、9=n66cn/n66tc、**9 Pro=n67cn/n67gl**、9 Active=n69cn、**10=o66cn**、Watch S3=mijia.watch.n62、S4=o62、Redmi Watch 4=n65、Redmi Watch 5=o65；命名规律（首字母=年份，第二位数字 6=手环 7=手环 Pro 2=Watch S 5=Redmi）。预设表全面修正（此前 q69cn/p62 等猜测错误）。
- [x] v1.4.0「表盘商店直取」：设置页选目标机型+填数字 faceId → 官方接口查询（显示表盘名）→ 下载 config_file(.bin) → 自动入本地安装队列（v1.3 通道）推送手环。完整闭环：**任意机型+任意 faceId → 官方商店直取 → 直装**，全程免登录。
- [x] 归档 v1.4.0 双件套 + CHANGELOG。

### 结论与依据
- 商店市场切换（服务端绑定决定）虽不可行，但**表盘商店数据本身公开可查**——绕过"市场"直接按 faceId 获取表盘文件是等效且更自由的实现。
- faceId 来源：get-mi-watchface 的范围枚举（wfIdRanges，如 120917300000-120917300999）/社区分享。

### 待办
- [ ] 用户装 v1.4.0：目标选手环 10（o66cn）+ 试一个 faceId → 查询/下载/入队/推送全链路验收。
- [ ] faceId 挖掘工具/指引（README 引 get-mi-watchface 或内置枚举）。
- [x] GitHub 同步完成：仓库 dian-sen/MiFitDeviceMask（103 文件经 Git Data API 推送，commit f9ca379）；README 重写（项目状态表/上游致谢表/许可证声明/免责摘要）+ 新增 docs/DISCLAIMER.md（完整法律声明：项目性质/商标/表盘版权/逆向与服务条款/上游许可边界/无担保与风险自担/隐私提示/责任限制/许可证保留）+ 仓库描述更新为"纯测试项目·功能尚未实现完成"。

---

---

---
### 已完成 / 待办 / 结论与依据 / 下一步
-->
