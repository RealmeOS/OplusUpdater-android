# Oppo/Oplus Updater Android Library (JAR) & CLI Tool

English | [简体中文](#简体中文)

A lightweight Android library and command-line tool to query OPlus/OPPO/realme official OTA update info using the same protocol as the stock updater app.

This library is a Kotlin re-implementation inspired by and based on the Go project Houvven/OplusUpdater — many thanks to the original author:
- https://github.com/Houvven/OplusUpdater

## Features
- **Android Library**: Integrate as JAR into Android apps
- **CLI Tool**: Standalone command-line interface for OTA queries
- **Multi-region Support**: CN, EU, IN, SG, RU, TR, TH, GL regions
- **Official `/update/v6` protocol** with the same encryption as the stock OPPO updater

## Requirements
- Android Library: minSdk 26 (Android 8.0+)
- CLI Tool: Java 21 or higher
- Dependencies: Kotlin + OkHttp + kotlinx.serialization-json

## Protocol (`/update/v6`)

All requests go to `POST https://<region host>/update/v6`.

1. **Key negotiation** — `POST /crypto/cert/upgrade` with body `{"biz":"ota"}` returns
   - `data.cert4Encrypt`: an X.509 certificate whose subject public key is RSA-2048
   - `data.version`: the `certVersion` that must be echoed back

2. **Session key** — a random 16-byte AES key is generated per request and wrapped with
   `cert4Encrypt` using `RSA/ECB/OAEPWithSHA-1AndMGF1Padding`. The **raw key bytes** are wrapped
   (not a base64 form), and the result is base64-encoded into `protectedKey`.

3. **Cipher info header**
   ```
   x-otci-cipherInfo: {"ota":{"protectedKey":"<b64>","version":<sceneVersion>,"certVersion":<long>}}
   ```
   `sceneVersion` is `(now + 24h)` expressed in 100 ns ticks, i.e. `(nowMillis + 86_400_000) * 10_000`.

4. **Request body** — the JSON payload is encrypted with `AES/CTR/NoPadding` using a random
   16-byte IV and wrapped as:
   ```json
   {"params":"{\"cipher\":\"<b64>\",\"iv\":\"<b64>\"}"}
   ```

5. **Response** — `{"body":"{\"cipher\":..,\"iv\":..}","errMsg":..,"responseCode":..}` where the
   inner envelope is decrypted with the same AES key.

`responseCode` 200 means an update is available, 2004 means none.

> **Full vs incremental package**: the server returns a **full package** when the `components`
> array in the request body is empty (the default). Supplying the installed component versions
> makes it answer with an incremental package, or with no package at all.

## Android Library Usage

### Gradle setup

**Option A: Include as a module (recommended)**
1) In `settings.gradle.kts` of your app project:
```kotlin
include(":updater")
```
2) In your app module `build.gradle.kts`:
```kotlin
dependencies {
    implementation(project(":updater"))
}
```

**Option B: Use the library as JAR (for non-Android projects)**
1) Put `updater.jar` under your project's libs directory
2) Add the JAR to your classpath and include required dependencies:
```kotlin
dependencies {
    implementation(files("libs/updater.jar"))
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.6.0")
}
```

### API
Package: `updater`

- `object Updater`
  - `fun getConfig(region: String): Config`
  - `fun queryUpdate(args: QueryUpdateArgs): ResponseResult`
- `data class QueryUpdateArgs` — `otaVersion`, `region`, `model`, `nvCarrier`, `deviceId`, `proxy`,
  `reqMode`
- `class Config` — `carrierID`, `host`, `language`
- `object Regions` — `CN, EU, IN, SG, RU, TR, TH, GL`
- `class ResponseResult` — `responseCode`, `errMsg`, `body: String?`, `decryptedBodyBytes: ByteArray`

### Usage (Kotlin)
```kotlin
import updater.QueryUpdateArgs
import updater.Regions
import updater.Updater

val args = QueryUpdateArgs(
    otaVersion = "PLK110_11.A",  // full OTA prefix of the model you want to query
    region = Regions.CN,
    model = "PLK110",            // optional, auto-filled from otaVersion if blank
    reqMode = "manual",          // manual (default), client_auto, server_auto or taste
)

val result = Updater.queryUpdate(args)
if (result.responseCode == 200L) {
    // result.decryptedBodyBytes is raw JSON bytes of the decrypted body
    val jsonText = String(result.decryptedBodyBytes)
} else {
    // 2004 means no update for that model/channel
}
```

## Command Line Usage

### Building the CLI
```bash
./gradlew :updater:fatJar
```

The built JAR files will be in `updater/build/libs/`:
- `updater-all.jar` - Standalone executable with all dependencies
- `updater.jar` - Library only (without dependencies)

### Running the CLI
```bash
java -jar updater-all.jar --help

# Full package for another model, queried from any device
java -jar updater-all.jar PLK110_11.A

# The newest branch is usually only served on the "taste" channel
java -jar updater-all.jar RMX5010_11.H --reqmode taste
```

### CLI Parameters
```
Usage: java -jar updater-all.jar [OTA_VERSION] [OPTIONS]

Options:
  -m, --model TEXT         Device model, e.g. PLK110 (default: prefix of OTA_VERSION)
  -r, --region TEXT        Server region: CN (default), EU, IN, SG, RU, TR, TH, GL
  -c, --carrier TEXT       nvCarrier, e.g. 10010111 (default: from region)
  -g, --device-id TEXT     64 hex char device id (default: 64 zeros)
  -p, --proxy TEXT         HTTP proxy, e.g. http://127.0.0.1:7890
      --reqmode TEXT       Channel: manual (default), client_auto, server_auto, taste
  -h, --help               Show this help message
```

## Notes
- `Updater.getConfig(region)` returns the carrier id, host and language for the region.
- If `otaVersion` is missing suffix parts, the library appends a default
  `.01_0001_197001010000` for compatibility.
- When `deviceId` is blank, a default 64-zeros device id is used.
- The public key used for `protectedKey` is fetched from `/crypto/cert/upgrade` on every query,
  so no certificate is hard-coded and certificate rotation needs no code change.

## Cross-model queries
The query target is described entirely by `otaVersion` (e.g. `PLK110_11.A`) and its `model` prefix.
Headers that describe the **local** build — `romVersion`, `androidVersion`, `colorOSVersion`,
`prjNum`, `marketName`, `brand`, `brandSota`, `osType`, `securityPatch`, ... — are **never sent**.
Filling them with the local device's values makes the request self-contradictory
(`model=PLK110` while carrying `prjNum=24600` and `brand=realme`) and the server answers
`2004 artifactV1Result is empty`. That is why a stock-style client can only query its own model.

The branch letter (`A` / `C` / `F` / `H` / `J`) selects the OS generation for that model: the newest
generation is usually served only on the `taste` channel, older ones on
`manual` / `client_auto` / `server_auto`. To look up an older release, change the letter.

## License
This library follows the license of this repository (see `LICENSE`).

## Credits
- Inspired by and re-implemented from the Go project: https://github.com/Houvven/OplusUpdater

---

## 简体中文

一个用于 Android 的轻量库和命令行工具，用官方协议查询 OPlus/OPPO/Realme 的系统更新信息

本库为 Kotlin 版本的重写，参考并致谢原 Go 实现：
- https://github.com/Houvven/OplusUpdater

### 功能特性
- **Android 库**: 可作为 JAR 集成到 Android 应用
- **命令行工具**: 独立的 OTA 查询 CLI 工具
- **多区域支持**: CN、EU、IN、SG、RU、TR、TH、GL 区域
- **官方 `/update/v6` 协议**：与官方更新器相同的加密方式

### 环境要求
- Android 库: minSdk 26（Android 8.0 及以上）
- CLI 工具: Java 21 或更高版本
- 依赖: Kotlin、OkHttp、kotlinx.serialization-json

### 协议（`/update/v6`）

1. **密钥协商**：`POST /crypto/cert/upgrade`，body 为 `{"biz":"ota"}`，返回
   - `data.cert4Encrypt`：X.509 证书，内含 RSA-2048 公钥
   - `data.version`：需要回传的 `certVersion`
2. **会话密钥**：每次请求随机生成 16 字节 AES key，用 `cert4Encrypt` 以
   `RSA/ECB/OAEPWithSHA-1AndMGF1Padding` 包裹**原始 key 字节**（非 base64），再 base64 作为 `protectedKey`
3. **密钥信息头**：`x-otci-cipherInfo: {"ota":{"protectedKey":..,"version":..,"certVersion":..}}`，
   其中 `version` = `(当前时间 + 24h)` 的 100ns 计数，即 `(nowMillis + 86_400_000) * 10_000`
4. **请求体**：JSON 用 `AES/CTR/NoPadding`（随机 16 字节 IV）加密后包成
   `{"params":"{\"cipher\":..,\"iv\":..}"}`
5. **响应**：`{"body":"{\"cipher\":..,\"iv\":..}","errMsg":..,"responseCode":..}`，用同一 AES key 解密

`responseCode` 200 表示有更新，2004 表示该机型/通道无更新，304 表示被服务端拒绝
（如 `security patch has problem`）。

> **全量包 / 增量包**：请求体中的 `components` 为空数组时（默认），服务端返回**全量包**；
> 填入已安装的组件版本则会返回增量包，或不下发任何包。

> **跨机型查询**：查询目标完全由 `otaVersion`（如 `PLK110_11.A`）及其 `model` 前缀决定。
> 描述**本机当前状态**的头（`romVersion`、`androidVersion`、`colorOSVersion`、`prjNum`、
> `marketName`、`brand`、`osType`、`securityPatch` 等）**一律不发送** —— 若把它们填成本机真值，
> 请求就会自相矛盾（`model=PLK110` 却带 `prjNum=24600`/`brand=realme`），服务端返回
> `2004 artifactV1Result is empty`。这就是"只能查本机、换机型查不到"的原因。

### 集成方式
**方式 A：作为模块引入（推荐）**
1) 在项目根 `settings.gradle.kts`：`include(":updater")`
2) 在应用模块 `build.gradle.kts`：`implementation(project(":updater"))`

**方式 B：使用 JAR 库（适用于非 Android 项目）**
将 `updater.jar` 放入 libs，并添加 `okhttp` 与 `kotlinx-serialization-json` 依赖。

### API
- `Updater.getConfig(region)`：获取地区配置（运营商、host、语言）
- `Updater.queryUpdate(args)`：发起查询，返回 `ResponseResult`
- `QueryUpdateArgs`：查询参数（见上文英文说明）
- `ResponseResult`：`responseCode/errMsg/body/decryptedBodyBytes`

### 使用示例（Kotlin）
```kotlin
val args = QueryUpdateArgs(
    otaVersion = "PLK110_11.A",   // 要查询机型的完整 OTA 前缀
    region = Regions.CN,
    model = "PLK110",             // 可省略，为空时自动取 otaVersion 的前缀
    reqMode = "manual",
)
val result = Updater.queryUpdate(args)
if (result.responseCode == 200L) {
    val bodyJson = String(result.decryptedBodyBytes)
} else if (result.responseCode == 2004L) {
    // 该机型/通道没有可用更新
}
```

### 命令行工具
```bash
./gradlew :updater:fatJar
java -jar updater-all.jar --help

# 任意机型都能查（无需本机是同型号）
java -jar updater-all.jar PLK110_11.A

# 最新分支通常只在 taste 通道下发
java -jar updater-all.jar RMX5010_11.H --reqmode taste
```

### 说明
- 若 `otaVersion` 缺少后缀，库会自动追加 `".01_0001_197001010000"` 以兼容。
- `otaVersion` 的分支字母（`A` / `C` / `F` / `H` / `J`）对应 OS 代际：最新一代通常只在
  `taste` 通道下发，旧一代在 `manual` / `client_auto` / `server_auto` 下发。查旧版本时把字母改掉即可。
- `deviceId` 为空时使用 64 个 `0` 的默认值。
- 用于 `protectedKey` 的公钥每次查询都从 `/crypto/cert/upgrade` 动态获取，不再硬编码，
  证书轮换无需改代码。

### 许可
遵循本仓库的 LICENSE（见 `LICENSE`）。
