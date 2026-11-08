# SimpleHook MCP Fork — 交接文档

本文档面向接手这个 fork 的人，说明**做了什么、为什么这样做、在哪里、怎么验证、还欠什么**。
协议层面的设计取舍见 [`DESIGN.md`](DESIGN.md)，面向使用者的说明见
[`app/src/main/assets/mcp/README.md`](../../app/src/main/assets/mcp/README.md)。

- 仓库：`Rune-cn/SimpleHook`（fork 自 `littleWhiteDuck/SimpleHook`）
- 基线提交：`6cb29f0`（上游 `main`）
- 当前版本：`v2.0.10-mcp1`
- 分支：`main`；发布通过 `v*` 标签触发

---

## 1. 一句话概述

在 SimpleHook（Android Xposed/LSPosed 模块）里内置了一个 **MCP 服务器**，让 Claude Desktop、
IDE 插件等 AI 客户端能够读取/修改 Hook 配置、查询抓取到的记录；并补上了一个**保活设置**页面，
解决前台服务被系统清理的问题。

---

## 2. 功能清单

### 2.1 MCP 服务

| 项 | 值 |
| --- | --- |
| 协议版本 | **`2026-07-28`**（无 `initialize` 握手，按请求携带 `_meta`） |
| 传输 | Streamable HTTP（默认 `127.0.0.1:57321`，路径 `/mcp`）<br>stdio 桥接（默认 `127.0.0.1:57322`） |
| 鉴权 | Bearer Token（`Authorization: Bearer shm_...`），首次启用自动生成 |
| 默认状态 | **关闭**，需在设置中手动开启 |
| 修改类工具 | **默认关闭**，需单独开启「允许修改配置与删除记录」 |
| 工具数量 | 14 个（只读 7 + 修改 7） |

**14 个工具**

只读：`server_info`、`list_apps`、`list_hook_configs`、`get_hook_config`、
`list_records`、`get_record`、`parse_hook_signature`

修改（`mutating = true`，受开关控制）：`set_hook_config`、`add_hook_rule`、
`delete_hook_rule`、`set_extension_config`、`enable_hook_config`、`delete_hook_config`、
`delete_records`

### 2.2 保活设置

入口：设置 → 实验功能 → 保活设置。三个能力：

1. **通知权限**（Android 13+ `POST_NOTIFICATIONS`），被永久拒绝时跳转系统设置页
2. **后台无限制**（电池优化豁免），提供「直接申请」和「打开列表手动设置」两条路
3. **自启动页**（MIUI/EMUI/ColorOS/vivo/Flyme 等），逐个 `resolveActivity` 探测，失败不假装成功

页面每次 `onResume` 都从系统重读状态，本地不缓存，**不会显示过期的“已授权”**。

### 2.3 自检

MCP 设置页底部「自检连接」，分三层定位故障：`PORT`（TCP 能不能连）→ `HTTP`（401/403/404/无响应）
→ `MCP`（JSON-RPC 握手与协议版本）。结果同时显示**应用版本**和**服务启动记录**。

---

## 3. 代码地图

```
app/src/main/java/me/simpleHook/feature/mcp/
├── protocol/          协议核心：不依赖任何 Android API，可在 JVM 单测中直接运行
│   ├── McpProtocolVersion.kt   协议版本、_meta 键名、HTTP 头名
│   ├── JsonRpc.kt              JSON-RPC 信封、Id 解析
│   ├── McpErrors.kt            规范错误码（-326xx / -32020 / -32022）与结构化错误
│   ├── McpRequestMeta.kt       每请求 _meta 解析与校验
│   ├── McpTool.kt              JSON Schema 构造器、类型化参数读取
│   ├── McpToolDefinition.kt    工具定义、注解、结果与内容块
│   ├── McpToolRegistry.kt      确定性排序、修改开关、异常兜底
│   └── McpServerCore.kt        discover / tools list / tools call 分发
├── tools/             14 个工具实现，只依赖 McpDataSource 接口
├── transport/
│   ├── HttpCodec.kt            最小 HTTP/1.1 读写（有长度/数量上限）
│   ├── StreamableHttpTransport.kt  Streamable HTTP 语义、鉴权、Origin、头校验
│   └── LineTransport.kt        行分隔 JSON-RPC（stdio 桥接用）
├── selfcheck/McpSelfCheck.kt   分阶段连通性自检
├── service/McpService.kt       前台服务、传输生命周期、启动记录
├── ui/McpSettingsActivity.kt   设置页
├── AndroidMcpDataSource.kt     McpDataSource 的 Android 实现（读写走既有仓库）
├── McpDataSource.kt            数据访问接口（便于用假实现做单测）
└── McpServerFactory.kt         组装工具 + 核心

app/src/main/java/me/simpleHook/feature/keepalive/   保活页（权限探测 + ROM 跳转）
scripts/
├── check_mcp_static.py     跨文件一致性检查（见 §6）
├── simplehook_mcp_stdio.py stdio ⇄ TCP 桥接脚本
└── summarize_tests.py      单元测试结果汇总
```

**关键设计**：`protocol/`、`tools/`、`selfcheck/` 三个包**不允许 import Android API**，
因此可以在普通 JVM 上跑单测（CI 无需模拟器）。这一条由 `check_mcp_static.py` 强制。

---

## 4. 数据流：一次写配置发生了什么

```
MCP 客户端 → StreamableHttpTransport → McpServerCore → SetHookConfigTool
                                                          ↓
                                              AndroidMcpDataSource.setRules
                                                          ↓
                     Room 更新 → ConfigRemoteSyncHelper 同步到目标应用读取位置
                                                          ↓
                                                    刷新 LSPosed 作用域
```

**注意**：配置写入后，目标应用需**重启**才生效（工具返回值里 `restartRequired: true`）。

MCP **只修改已存在的配置**，不会为新应用创建配置——避免 AI 客户端凭空给任意应用挂 Hook。

---

## 5. 构建与发布

### 本地构建

```bash
git clone --recurse-submodules https://github.com/Rune-cn/SimpleHook.git
cd SimpleHook
bash ./gradlew :app:assembleRootDebug
```

`third_party/ARSCLib` 是 submodule，必须 `--recurse-submodules`，否则配置阶段失败。

### CI（`.github/workflows/android.yml`）

| 作业 | 触发 | 内容 |
| --- | --- | --- |
| `verify` | push / PR（非 tag） | 静态检查 → 单元测试 → debug APK |
| `release` | `v*` 标签 | 静态检查 → 测试 → **签名 release APK** → 产物上传 |
| `nightly` | push 到分支 | 带日期与短 SHA 的 debug APK |

单测汇总会写进 GitHub Step Summary（`total=64 failed=0` 这种）。

### 发布步骤

```bash
git tag v2.0.11-mcp1
git push origin refs/tags/v2.0.11-mcp1
```

产物在 Actions 运行页的 Artifacts 中，名如 `SimpleHook-release-2.0.11-mcp1`，
内含 APK + `SHA256SUMS.txt` + `BUILD-INFO.txt`（版本/提交/构建时间）。

### 发布页默认不创建

`Create GitHub release` 步骤由仓库变量控制：

```
Settings → Variables → PUBLISH_RELEASE=true   # 才会创建 GitHub Release 页面
```

不设置则**只产出签名 APK 作为构建产物，不发布到 Release 页面**。

### 签名

4 个仓库 Secrets（已配置）：`KEYSTORE_BASE64`、`SIGNING_ALIAS`、
`SIGNING_STORE_PASSWORD`、`SIGNING_KEY_PASSWORD`。

- 算法：PKCS12 / RSA 4096 / SHA384withRSA，有效期至 **2054-03-26**
- 证书 SHA-256：`1D:68:19:70:F3:9B:42:C1:56:80:CC:C4:DF:70:0F:5F:56:E9:A6:3F:50:35:96:E1:D3:F9:34:46:6F:14:33:A5`
- 证书主体：`CN=SimpleHook MCP Fork, OU=Personal Build, O=Rune-cn, ...`

> ⚠️ **keystore 必须备份**。丢失后新包无法覆盖安装（证书变化），用户只能卸载重装。
> 当前副本位于构建者工作区 `/workspace/keys/simplehook-release.jks`，
> 密码见 `/workspace/RELEASE-CREDENTIALS.txt`。

---

## 6. 验证手段

### 6.1 单元测试（64 个）

| 测试类 | 数量 | 覆盖 |
| --- | --- | --- |
| `McpServerCoreTest` | 12 | 版本协商、`_meta` 校验、discover、分页、错误码 |
| `McpToolsTest` | 11 | 参数校验、修改开关、异常不泄漏敏感信息 |
| `TransportTest` | 18 | 真实回环 socket：头校验、鉴权、Origin、状态码、通知 202、IPv4 绑定、重复启动幂等 |
| `McpSelfCheckTest` | 7 | 自检器通过路径与每种失败分支 |

其余为上游原有测试（16 个）：共 64 个。

### 6.2 静态检查 `scripts/check_mcp_static.py`

这是**跨文件一致性**检查，弥补“本地无法编译”的空档。CI 第一步就跑它。当前检查项：

1. 布局/代码引用的 `@string`、`@drawable`、`R.string` 是否存在
2. ViewBinding id 与布局 id 是否对应
3. 工具注册唯一性、名称合法、未被遗漏注册
4. `protocol/`、`tools/` **不得 import Android**；`feature/` 不得把框架属性写成库 R 类
5. `feature/mcp` 内使用的协议符号是否都已 import 或同包声明
6. 前台服务类型必须是 `specialUse`，清单与 `startForeground` 类型一致
7. 保活页资源、清单声明、三语字符串一致性
8. MCP / 保活文案在 `values`、`values-en`、`values-zh-rTW` 三处齐全

新增功能时**请同步扩展这个脚本**——它已经真实拦下过多次编译错误。

### 6.3 设备自检

设置页「自检连接」。正常情况下应显示：

```
自检通过：端口、HTTP、MCP 握手均正常
supportedVersions=[2026-07-28, ...], capabilities=[tools]
应用版本：2.0.10-mcp1
服务启动记录： onStartCommand → foreground=ok → httpEnabled=true port=57321 → serverCore=built → http=bound:127.0.0.1:57321 live=true
```

排障对应关系：

| 启动记录 / 结果 | 含义 |
| --- | --- |
| `服务启动记录：无` | 服务从未启动（开关没生效 / 被系统拦截） |
| `foreground=refused` | 前台服务被拒绝（权限或 ROM 策略） |
| `http=failed:BindException` | 端口被占用，换端口 |
| 绑定地址是 `::1` 或 `0:0:0:0:0:0:0:1` | 绑到了 IPv6，IPv4 客户端连不上 |
| `http=bound:127.0.0.1:57321 live=true` 但自检仍失败 | 服务正常，问题在客户端侧 |

---

## 7. 已修复的坑（按发生顺序）

这些都是**在真机上暴露、已修复并有测试或静态检查兜底**的问题，接手时不必重踩：

| # | 问题 | 根因 | 修复 |
| --- | --- | --- | --- |
| 1 | 启动即崩溃 `SecurityException` | `connectedDevice` 类型 FGS 在 targetSdk 34+ 还要求蓝牙/NFC 等运行时权限 | 改为 `specialUse` + subtype 声明 |
| 2 | 端口 `ECONNREFUSED` | `startForeground()` 在绑定端口之后调用，冷启动超时被系统杀进程 | 前台提升提到第一句 |
| 3 | 同上（二次） | 重复启动时 `stopServers()` 关掉了刚绑定的 socket | `startInProgress` 让重复启动变空操作 |
| 4 | 同上（三次） | `InetAddress.getLoopbackAddress()` 在 IPv6 优先设备返回 `::1` | 显式绑定 `127.0.0.1` |
| 5 | 服务被杀后无法自愈 | 只有开关回调会启动服务 | `onResume` 检测到“已开启但未监听”时补启动 |
| 6 | 自检永远连不上 | `Socket().apply { connect(..., port) }` 里 `port` 解析成 `Socket.port`（-1） | 地址在 `apply` 外先算好 |

另外纠正过一个**测试假象**：诊断测试一度只验证裸 socket、没有真正调用自检器，
导致“诊断通过但自检失败”的误导。现已改为让诊断直接调用被测对象。

---

## 8. 安全模型

- 只监听 `127.0.0.1`，不对外网暴露
- 强制 Bearer Token；比较使用 `MessageDigest.isEqual`（常数时间）
- 校验 `Origin`，非法来源返回 403（防 DNS rebinding）
- 请求体、头部数量、行长度均有上限
- 工具异常**不把原始异常消息**回传模型（可能含路径/密钥），只给异常类型
- 记录内容含账号令牌等敏感信息，UI 与文档均已警示

---

## 9. 已知限制 / 待办

**功能层面**

1. `set_extension_config` 的 `patch` 是自由对象，键名合法性由 `AndroidMcpDataSource.unchecked`
   的深合并负责，**未知键会被静默写入**。若要严格，应在数据源里按 `ExtensionConfig`
   的 schema 做白名单校验。
2. MCP 不能为新应用创建配置（有意为之）。若产品上需要，应新增独立工具并单独授权。
3. stdio 桥接脚本 `scripts/simplehook_mcp_stdio.py` 未纳入 CI 自动化测试，仅做过语法与手工验证。
4. 保活页的 ROM 自启动组件是未公开 API，随 ROM 版本可能失效；失败时已退回应用详情页。

**工程层面**

5. 本地无法编译（构建者环境缺 Android 工具链），所有编译验证依赖 CI，**单次反馈约 3 分钟**。
   接手时若本地能编译，会明显更快。
6. `docs/mcp/DESIGN.md` 写在功能实现初期，部分细节（如 `specialUse` 之外还改过多次）
   以本文档为准。
7. 未做多语言之外的本地化；`values` 默认即简体中文。

---

## 10. 常用命令速查

```bash
# 本地构建
bash ./gradlew :app:assembleRootDebug

# 只跑单元测试
bash ./gradlew :app:testRootDebugUnitTest

# 跑单个测试类
bash ./gradlew :app:testRootDebugUnitTest --tests "*McpSelfCheckTest"

# 静态检查（改完 资源/清单/工具 后建议本地先跑）
python3 scripts/check_mcp_static.py

# 发布
git tag v2.0.11-mcp1 && git push origin refs/tags/v2.0.11-mcp1

# 校验签名（需要 Android build-tools）
apksigner verify --print-certs path/to/app.apk
```

---

## 11. 改动范围

相对上游 `6cb29f0`，新增/修改的文件：

- **新增**：`feature/mcp/**`（21 个文件）、`feature/keepalive/**`（3 个文件）、
  `app/src/test/.../feature/mcp/**`（5 个文件）、`scripts/**`（3 个）、
  `docs/mcp/**`、`activity_mcp_settings.xml`、`activity_keep_alive.xml`、
  `ic_settings_hub_24.xml`、`ic_settings_keepalive_24.xml`、
  `app/src/main/assets/mcp/README.md`
- **修改**：`AndroidManifest.xml`（权限 + 2 个组件）、`settings_preferences.xml`（2 个入口）、
  `SettingsFragment.kt`（入口跳转）、`SPUtil.kt`（MCP 配置项与令牌生成）、
  4 个 Dao/Repository（MCP 查询接口）、`ConfigRemoteSyncHelper.kt`（阻塞变体）、
  `strings.xml` ×3（三语文案）、`.github/workflows/android.yml`（CI）

上游原有代码逻辑**未改动**，只在既有类上追加了方法。
