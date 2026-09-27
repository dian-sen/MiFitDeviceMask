<div align="center">

# MiFitDeviceMask

**小米运动健康 · 机型/表盘测试模块（LSPosed · libxposed API 102）**

⚠️ **纯测试项目 · 功能尚未实现完成 · 仅供个人学习研究** ⚠️

非官方项目 · 与小米公司无任何关联 · 使用前必读 [DISCLAIMER.md](docs/DISCLAIMER.md)

</div>

---

## 项目状态（2026-09-27 · v1.4.0）

| 能力 | 状态 | 说明 |
| --- | --- | --- |
| 表盘商店直取 | ✅ 已实现 | 官方公开接口免登录查询任意机型+faceId 的表盘并下载 |
| 本地表盘文件直装 | ⚠️ 待验证 | doInstall/preInstall 通道推送到手环，兼容性由手环固件判定 |
| 切换表盘商店市场 | ❌ 已验证不可行 | 市场由服务端账号绑定决定，客户端参数被忽略（详见 ROADMAP） |
| 本地机型/身份改写 | ❌ 已废弃 | 破坏连接且被 App 持久化（危险开关默认关，仅存档） |

> 详细验证过程见 [docs/WORKLOG.md](docs/WORKLOG.md) 与 [docs/ROADMAP.md](docs/ROADMAP.md)。

## 免责声明与法律提示（摘要，完整版见 [docs/DISCLAIMER.md](docs/DISCLAIMER.md)）

- 本项目**仅供个人学习与研究**，禁止商用；下载的表盘资源版权归原作者，**不得二次分发**
- 非官方项目，与小米公司无任何关联；Xiaomi / 小米 / Redmi 等商标归其权利人所有
- 项目涉及对目标应用的运行时观察与公开资料分析，**可能触及目标应用用户协议的受限条款**，合规性由使用者自行判断
- 使用风险自担：不保证可用性；手环异常、数据丢失、**小米账号被风控/限制**等后果由使用者承担
- 不得用于绕过付费表盘授权等侵犯知识产权的用途

## 上游参考与致谢（排名不分先后）

本项目**未复制任何上游项目的代码**，仅参考其公开文档、公开接口事实与设计思路，在此致谢：

| 项目/资源 | 参考内容 | 链接 |
| --- | --- | --- |
| get-mi-watchface | 官方表盘商店公开查询接口、机型代号映射表 | [aurysian-yan/get-mi-watchface](https://github.com/aurysian-yan/get-mi-watchface) |
| libxposed/api | 现代 Xposed API（API 101/102） | [libxposed/api](https://github.com/libxposed/api) |
| MiFitnessAdAway | 同目标 App 的 API 102 模块工程结构参考 | [hao1196561270/MiFitnessAdAway](https://github.com/hao1196561270/MiFitnessAdAway) |
| Wearable-Debug | 表盘安装通道（doInstall/preInstall）事实参考 | [A5245/Wearable-Debug](https://github.com/A5245/Wearable-Debug) |
| Gadgetbridge | 小米可穿戴 BLE 协议公开分析 | [Freeyourgadget/Gadgetbridge](https://github.com/Freeyourgadget/Gadgetbridge) |
| 米坛社区 BandBBS | 表盘资源与社区知识 | [bandbbs.cn](https://www.bandbbs.cn/) |
| 玩转小米手环8第三方表盘（pzqqt 博客） | 表盘下载替换原理 | [博客文章](https://pzqqt.github.io/2024/07/21/%E7%8E%A9%E8%BD%AC%E5%B0%8F%E7%B1%B3%E6%89%8B%E7%8E%AF-8-%E7%9A%84%E7%AC%AC%E4%B8%89%E6%96%B9%E8%A1%A8%E7%9B%98.html) |

各上游项目的许可证以其仓库为准；若本项目任何内容侵犯了您的权益，请通过 Issue 联系，将立即处理。

## 功能简介（当前 v1.4.0）

- **表盘商店直取**：目标机型（预设/自定义内部代号）+ 表盘数字 faceId → 官方公开接口查询 → 下载 .bin → 进入本地安装队列
- **本地表盘安装**：任意来源的表盘 .bin 文件 → 经安装通道推送到手环（需先在商店正常安装任意表盘以捕获安装器）
- **观察/诊断模式**：默认不改写本地身份（历史版本验证：改写会破坏连接且被 App 持久化），全量请求侦察 + 探测日志

## 构建与安装

- 一键构建（免 Gradle）：`tools\build-apk.ps1`（需按脚本注释准备 JDK 与依赖缓存）；语法校验用 `tools\build-check\verify.bat`
- 或用 Android Studio（Ladybug+，AGP 8.5）直接打开工程构建
- 历史版本 APK 见 [dist/](dist/) 与 [docs/versions/](docs/versions/)

## 激活与使用

1. LSPosed 启用本模块 → 作用域勾选「小米运动健康」→ 重启
2. 打开模块 App →「当前机型 · model」填你手环的内部代号（如手环 9 Pro 填 `n67cn`）→ 保存
3. 表盘获取见设置页两个区块（商店直取 / 本地文件安装）
4. 出问题回滚：LSPosed 停用模块 → 重启手机 → 必要时清除小米运动健康数据重新配对

完整测试与排障流程见 [docs/WORKFLOW.md](docs/WORKFLOW.md)，开发日志见 [docs/WORKLOG.md](docs/WORKLOG.md)。

## 许可证

**All Rights Reserved © 2026 dian-sen**。未选择开源许可证：**未经作者书面许可，不得复制、分发、修改或再发布本项目代码**。上游项目版权归原作者；如需引用本项目内容，请先开 Issue 联系。
