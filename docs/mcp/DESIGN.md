# SimpleHook MCP Server — Design

This document records the protocol research and the architecture decisions behind the
MCP (Model Context Protocol) integration. Every protocol fact below carries the source
page and the revision it was read from.

## 1. Protocol revision

| Revision      | Status at time of writing | Source |
| ------------- | ------------------------- | ------ |
| `2026-07-28`  | Latest published revision (tag `2026-07-28`) | `docs/specification/2026-07-28/` of `modelcontextprotocol/modelcontextprotocol`, read 2026-11-08 |
| `2025-11-25`  | Previous revision (legacy, `initialize` handshake) | same tree |
| `2025-06-18`, `2025-03-26`, `2024-11-05` | Older revisions | same tree |

The server speaks **`2026-07-28` only**. Facts taken from the specification:

* There is **no `initialize` handshake**. Every request carries its protocol version in
  `params._meta["io.modelcontextprotocol/protocolVersion"]` and its capabilities in
  `io.modelcontextprotocol/clientCapabilities`; a required field that is missing makes
  the request malformed ⇒ JSON-RPC `-32602` and HTTP `400`.
  (`specification/2026-07-28/basic/index.mdx` § `_meta`; `server/discover.mdx`)
* MCP is a **stateless protocol**: a server must not infer context from earlier requests
  or from the connection. `2026-07-28` removes `Mcp-Session-Id`, standalone `GET` SSE
  streams, server→client requests on a response stream and resumable streams.
  (`basic/index.mdx` § Statelessness; `basic/transports/streamable-http.mdx` § Earlier
  Streamable HTTP Revisions)
* `server/discover` is mandatory for servers; it returns `supportedVersions`,
  `capabilities`, `_meta["io.modelcontextprotocol/serverInfo"]` and optional
  `instructions`. (`server/discover.mdx`)
* An unsupported requested version ⇒ `-32022` `UnsupportedProtocolVersionError` with
  `data.supported` / `data.requested`, HTTP `400`. (`basic/versioning.mdx`)
* Result objects carry `resultType: "complete"`. (`basic/index.mdx` § ResultType)

### Transports

Two cores are implemented, both inside the app, with no new third-party dependencies:

1. **Loopback Streamable HTTP** (default) — for desktop MCP clients on the same device
   (Claude Desktop after `adb forward`, IDE plugins, curl-based tooling). Implemented
   requirements (`basic/transports/streamable-http.mdx`):
   * one endpoint path (`/mcp`), `POST` only; `GET`/`DELETE` ⇒ `405`
   * `Accept` must list `application/json` and `text/event-stream`
   * every POST carries `MCP-Protocol-Version`, `Mcp-Method`, and `Mcp-Name` for
     `tools/call` (Base64 sentinel encoding for non-ASCII values)
   * header/body mismatch, missing or malformed header ⇒ HTTP `400` + `-32020`
     `HeaderMismatch`
   * unknown method ⇒ HTTP `404` + `-32601`
   * `Origin` present and not allow-listed ⇒ HTTP `403` (DNS-rebinding protection)
   * replies are `application/json`; notifications ⇒ `202 Accepted`
   * binds to loopback only
2. **stdio-over-TCP** — for hosts that can spawn a process (Termux/proot). The app opens
   a loopback TCP port; `scripts/simplehook_mcp_stdio.py` (Python 3, stdlib only) shuttles
   newline-delimited JSON-RPC between the host's stdio and that socket. Same JSON-RPC core
   and the same framing rule (one message per line).

### Authorization

`basic/authorization/index.mdx` targets HTTP transports and is OAuth-centric, while
allowing implementations to negotiate their own scheme. The endpoint is loopback-bound
and exposes sensitive data (hook configs, captured records), so the app issues a
**bearer token** (`Authorization: Bearer <token>`) that the user copies into the client
configuration, and additionally enforces an `Origin` allow-list. Auth failures are HTTP
`401` with `WWW-Authenticate: Bearer`.

## 2. Architecture

```
app/src/main/java/me/simpleHook/feature/mcp/
├── protocol/   transport-agnostic JSON-RPC / MCP core (no Android APIs, unit-tested)
│   ├── McpProtocolVersion.kt  revisions and capability constants
│   ├── JsonRpc.kt             envelopes, ids, error codes
│   ├── McpErrors.kt           spec error codes (-326xx, -32020, -32021, -32022)
│   ├── McpRequestMeta.kt      per-request _meta parsing and validation
│   ├── McpTool.kt             tool definition, JSON-Schema helpers, argument reader
│   ├── McpToolRegistry.kt     deterministic ordering, capability filter, dispatch
│   └── McpServerCore.kt       server/discover, tools/list, tools/call, notifications
├── tools/      domain tools backed by the existing repositories
├── transport/  StreamableHttpTransport (ServerSocket + minimal HTTP/1.1), LineTransport
└── ui/         settings screen, foreground service
```

The protocol core depends on **no Android API**, so it runs under plain JUnit in CI.
Android access is injected through interfaces (`McpDataSource`), which keeps the tools
testable with fakes.

## 3. Tool set

Read-only tools are always available. Mutating tools sit behind a user-controlled switch
and are annotated (`readOnlyHint`, `destructiveHint`) in `annotations`.

| Tool | Purpose |
| ---- | ------- |
| `server_info` | App version, protocol revision, transport endpoints, feature switches |
| `list_apps` | Installed packages (name, version, system flag), paged |
| `list_hook_configs` | Configured apps with rule/extension summaries |
| `get_hook_config` | Full hook rules + extension config for one package |
| `set_hook_config` | Replace the hook rules of a package |
| `add_hook_rule` | Append one rule (mode enum documented in the schema) |
| `delete_hook_rule` | Delete a rule by index |
| `set_extension_config` | Patch extension switches (popup / json / clipboard / file …) |
| `enable_hook_config` | Enable or disable a package configuration |
| `delete_hook_config` | Delete the configuration and its synced external copies |
| `list_records` | Query captured records (package/type/keyword/time/mark), paged |
| `get_record` | Full payload of one record |
| `delete_records` | Delete by id list, or all records of a package |
| `parse_hook_signature` | Convert a Smali member signature into a hook rule |

## 4. Security model

* The server is **off by default**; starting it requires an explicit foreground service.

### Foreground-service type

The service is declared as `android:foregroundServiceType="specialUse"` with a
`PROPERTY_SPECIAL_USE_FGS_SUBTYPE` of `mcp_local_server`, and `startForeground()` on
Android 14+ passes `FOREGROUND_SERVICE_TYPE_SPECIAL_USE`.

`connectedDevice` is **wrong here** even though the server is a network listener: from
Android 14 (targetSdk 34) that type additionally requires one of `BLUETOOTH_CONNECT`,
`CHANGE_NETWORK_STATE`, `CHANGE_WIFI_STATE`, `CHANGE_WIFI_MULTICAST_STATE`, `NFC`,
`TRANSMIT_IR`, … at runtime (`attrs_manifest.xml` in the platform SDK, `connectedDevice`
flag). A loopback-only socket does not need any of them, and requesting them would widen
the app's privacy surface for nothing; starting the FGS without them throws
`SecurityException` (verified on device, targetSdk 36).

`scripts/check_mcp_static.py` fails the build if the manifest type and the runtime type
diverge, or if a restricted type is used without its prerequisite permission.
* Loopback bind only, bearer token required, `Origin` allow-list.
* Every tool argument is validated against its declared schema before use.
* Mutating tools can be disabled entirely; the UI shows the active mode.
* Records contain secrets — the UI warns, and no tool ever logs payloads.

## 5. Verification strategy

No local build is performed (the sandbox cannot run the x86-64 Android toolchain). All
compilation and tests run in GitHub Actions (`.github/workflows/android.yml`):
`bash ./gradlew :app:testRootDebugUnitTest :app:assembleRootDebug`. The protocol core,
tool registry, request validation and the HTTP transport are covered by JVM unit tests
in `app/src/test/java/me/simpleHook/feature/mcp/`.
