# SimpleHook MCP 服务

SimpleHook 内置一个 [MCP](https://modelcontextprotocol.io)（Model Context Protocol）服务，
让 Claude Desktop、IDE 插件等 AI 客户端直接读取和修改 Hook 配置、查询抓取到的记录。

- 协议版本：**MCP `2026-07-28`**（当前正式版，无 `initialize` 握手，按请求携带 `_meta`）
- 传输：**Streamable HTTP**（本机回环）与 **stdio 桥接**（配套 Python 脚本）
- 默认状态：**关闭**，需要在应用内手动开启
- 协议与安全设计详见 [`docs/mcp/DESIGN.md`](../../docs/mcp/DESIGN.md)

## 1. 在手机上开启

1. 打开 SimpleHook → **设置** → **实验功能** → **MCP 服务**。
2. 打开「启用 MCP 服务」。应用会以常驻通知的形式运行服务（前台服务）。
3. 页面会显示：
   - 服务地址，例如 `http://127.0.0.1:57321/mcp`
   - 访问令牌，例如 `shm_1f3c…`
   - 「复制客户端配置」按钮，生成可直接粘贴的 JSON

端口可在页面上修改（1024–65535），修改后需重新启动服务。首次启用时会自动生成令牌。

## 2. 连接方式

### 2.1 桌面客户端（Streamable HTTP）

把下面这段配置加入客户端的 `mcpServers`（令牌与端口以应用内显示为准）：

```json
{
  "mcpServers": {
    "simplehook": {
      "type": "http",
      "url": "http://127.0.0.1:57321/mcp",
      "headers": {
        "Authorization": "Bearer shm_xxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxx"
      }
    }
  }
}
```

如果客户端不在手机上，先用 adb 把端口转发到本机：

```bash
adb forward tcp:57321 tcp:57321
```

### 2.2 stdio 客户端

部分客户端只支持启动子进程式的 stdio server。使用仓库中的桥接脚本：

```bash
adb forward tcp:57322 tcp:57322      # 与应用内「stdio 桥接端口」一致
python3 scripts/simplehook_mcp_stdio.py --token shm_xxxxxxxx
```

客户端配置：

```json
{
  "mcpServers": {
    "simplehook": {
      "command": "python3",
      "args": ["/path/to/scripts/simplehook_mcp_stdio.py", "--token", "shm_xxxxxxxx"]
    }
  }
}
```

## 3. 可用工具

| 工具 | 作用 | 需要开启「允许修改」 |
| --- | --- | :---: |
| `server_info` | 服务版本、协议版本、端点、记录类型 | |
| `list_apps` | 列出已安装应用 | |
| `list_hook_configs` | 列出已有配置的应用及规则数量 | |
| `get_hook_config` | 读取某个应用的完整配置 | |
| `list_records` | 查询抓取记录 | |
| `get_record` | 读取单条记录的完整内容 | |
| `parse_hook_signature` | Smali 签名转 Hook 规则 | |
| `set_hook_config` | 整体替换某应用的规则 | ✓ |
| `add_hook_rule` | 追加一条规则 | ✓ |
| `delete_hook_rule` | 按序号删除规则 | ✓ |
| `set_extension_config` | 合并修改扩展功能开关 | ✓ |
| `enable_hook_config` | 启用/停用配置 | ✓ |
| `delete_hook_config` | 删除配置 | ✓ |
| `delete_records` | 删除记录 | ✓ |

说明：

- **修改类工具默认不可用**，需在设置中打开「允许修改配置与删除记录」。
- MCP 只修改**已存在**的配置；新应用的配置请在应用内创建。
- 配置写入后会同步到目标应用读取的位置并刷新 LSPosed 作用域，但**需要重启目标应用**才生效。
- 记录的 `recordJson` 可能包含账号、令牌等敏感信息，请谨慎提供给模型。

## 4. 示例：用 curl 调试

```bash
TOKEN=shm_xxxxxxxx
VERSION=2026-07-28

# server/discover
curl -s http://127.0.0.1:57321/mcp \
  -H "Authorization: Bearer $TOKEN" \
  -H "Content-Type: application/json" \
  -H "Accept: application/json, text/event-stream" \
  -H "MCP-Protocol-Version: $VERSION" \
  -H "Mcp-Method: server/discover" \
  -d "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"server/discover\",\"params\":{\"_meta\":{\"io.modelcontextprotocol/protocolVersion\":\"$VERSION\",\"io.modelcontextprotocol/clientCapabilities\":{}}}}"

# tools/call 需要额外带 Mcp-Name
curl -s http://127.0.0.1:57321/mcp \
  -H "Authorization: Bearer $TOKEN" \
  -H "Content-Type: application/json" \
  -H "Accept: application/json, text/event-stream" \
  -H "MCP-Protocol-Version: $VERSION" \
  -H "Mcp-Method: tools/call" \
  -H "Mcp-Name: list_hook_configs" \
  -d "{\"jsonrpc\":\"2.0\",\"id\":2,\"method\":\"tools/call\",\"params\":{\"name\":\"list_hook_configs\",\"arguments\":{},\"_meta\":{\"io.modelcontextprotocol/protocolVersion\":\"$VERSION\",\"io.modelcontextprotocol/clientCapabilities\":{}}}}"
```

## 5. 安全提示

- 服务**只监听 `127.0.0.1`**，并且校验 `Origin` 头，其他网页无法通过 DNS rebinding 访问。
- 所有请求都必须带 `Authorization: Bearer <令牌>`；令牌可在设置页重置。
- 不需要时请关闭服务；关闭后端口不再监听。
- 只在你拥有或明确获授权测试的应用上使用。

## 6. 构建与测试

所有编译与单元测试在 GitHub Actions 中执行（本地无需配置 SDK）：

```bash
# 完整校验（静态检查 + 单元测试 + debug 构建）
gh workflow run android.yml
```

- 单元测试：`app/src/test/java/me/simpleHook/feature/mcp/`
  - `McpServerCoreTest`：版本协商、`_meta` 校验、`server/discover`、分页、错误码
  - `McpToolsTest`：工具参数校验、修改开关、敏感信息不外泄
  - `TransportTest`：真实回环 socket 上的 HTTP 头校验、鉴权、`Origin`、状态码、stdio 桥接
- 静态一致性检查：`python3 scripts/check_mcp_static.py`
