package updater

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.net.InetSocketAddress
import java.net.Proxy
import java.net.URL
import java.nio.charset.StandardCharsets
import java.security.KeyFactory
import java.security.SecureRandom
import java.security.interfaces.RSAPublicKey
import java.security.spec.X509EncodedKeySpec
import java.util.Base64
import java.util.Locale
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * OPlus / OPPO / realme OTA query client, protocol `/update/v6`.
 *
 * Wire format (reverse engineered from `com.oplus.ota` 17.0.3):
 *  - `POST /crypto/cert/upgrade` body `{"biz":"ota"}` returns `data.cert4Encrypt` (X.509, RSA-2048)
 *    and `data.version`, which is the `certVersion` sent back to the server.
 *  - A random AES key is generated per request and wrapped with `cert4Encrypt` using
 *    `RSA/ECB/OAEPWithSHA-1AndMGF1Padding`; the raw key bytes (not a base64 form) are wrapped.
 *  - `x-otci-cipherInfo: {"ota":{"protectedKey":<b64>,"version":<sceneVersion>,"certVersion":<long>}}`
 *  - Request body is `{"params":"{\"cipher\":\"<b64>\",\"iv\":\"<b64>\"}"}` where the inner
 *    envelope is `AES/CTR/NoPadding` of the JSON request body.
 *  - The response body is the same envelope and is decrypted with the same AES key.
 *
 * Cross-model support:
 *  - The query target is fully described by `otaVersion` (e.g. `PLK110_11.A`) and its `model` prefix.
 *  - Headers that describe the *local* build (`romVersion`, `androidVersion`, `colorOSVersion`,
 *    `prjNum`, `marketName`, `brand`, `osType`, `securityPatch`, ...) are **not** sent. Filling them
 *    with the local device's values makes the request self-contradictory for any other model and the
 *    server answers `2004` (`artifactV1Result is empty`).
 *  - `responseCode` 200 = update available, 2004 = none for that model/channel, 304 = rejected
 *    (e.g. `security patch has problem`).
 */
object Updater {

    const val ENDPOINT_CERT = "/crypto/cert/upgrade"
    const val ENDPOINT_UPDATE = "/update/v6"

    /** biz/scene used by the OTA app. */
    private const val BIZ = "ota"

    /** Client protocol versions advertised by the OTA app. */
    private const val PROTOCOL_VERSION = "3"
    private const val RSA_VERSION = "3"
    private const val INF_VERSION = "1"

    /**
     * Placeholder sent for the "current local build" headers. The stock updater fills these with the
     * device's real values, but it only ever queries for itself; a cross-model tool must not, or the
     * request contradicts its own `model`/`otaVersion` and the server answers `2004`.
     */
    private const val UNKNOWN = "unknown"

    /** Unlocked carrier pipeline, valid for every region. */
    private const val PIPELINE_KEY = "ALLNET"

    /** Request body constants; an empty `components` array requests the full package. */
    private const val BODY_MODE = 0
    private const val BODY_TYPE = "1"
    private const val STRATEGY_VERSION = "9"

    private const val AES_KEY_BYTES = 16
    private const val AES_IV_BYTES = 16

    /** Default 64 hex zeros used when no device id is supplied. */
    private const val DEFAULT_DEVICE_ID =
        "0000000000000000000000000000000000000000000000000000000000000000"

    fun getConfig(region: String): Config = resolveConfig(region)

    fun queryUpdate(args: QueryUpdateArgs): ResponseResult = queryUpdateV6(args)

    // ------------------------------------------------------------------ cert

    data class CertInfo(val publicKey: RSAPublicKey, val certVersion: Long)

    private fun fetchCert(client: OkHttpClient, host: String): CertInfo {
        val url = "https://$host$ENDPOINT_CERT"
        val request = Request.Builder()
            .url(url)
            .post("""{"biz":"$BIZ"}""".toRequestBody(JSON_MEDIA_TYPE))
            .build()
        val body = client.newCall(request).execute().use { response ->
            response.body?.string().orEmpty()
        }
        val root = Json.parseToJsonElement(body).jsonObject
        val data = root["data"]?.jsonObject
            ?: throw IllegalStateException("cert response has no data: $body")
        val pem = data["cert4Encrypt"]?.jsonPrimitive?.contentOrNull
            ?: throw IllegalStateException("cert response has no cert4Encrypt")
        val version = data["version"]?.jsonPrimitive?.contentOrNull?.toLongOrNull()
            ?: throw IllegalStateException("cert response has no version")
        return CertInfo(parseX509PublicKey(pem), version)
    }

    private fun parseX509PublicKey(pem: String): RSAPublicKey {
        val der = Base64.getMimeDecoder().decode(
            pem.replace("-----BEGIN CERTIFICATE-----", "")
                .replace("-----END CERTIFICATE-----", "")
                .replace("\r", "")
                .replace("\n", "")
                .trim()
        )
        // cert4Encrypt is a certificate; the RSA public key is the SPKI bit string.
        val spki = extractSubjectPublicKeyInfo(der)
        val key = KeyFactory.getInstance("RSA").generatePublic(X509EncodedKeySpec(spki))
        return key as RSAPublicKey
    }

    /**
     * Minimal DER walk: Certificate -> TBSCertificate -> SubjectPublicKeyInfo.
     * Avoids depending on a full X.509 parser.
     */
    private fun extractSubjectPublicKeyInfo(certDer: ByteArray): ByteArray {
        val reader = DerReader(certDer)
        val cert = reader.readSequence()          // Certificate
        val tbs = cert.readSequence()             // TBSCertificate
        tbs.readExplicitTag(0xA0)                 // version [0] EXPLICIT, optional
        tbs.readAny()                             // serialNumber
        tbs.readAny()                             // signature
        tbs.readAny()                             // issuer
        tbs.readSequence()                        // validity
        tbs.readAny()                             // subject
        return tbs.readElementBytes()             // SubjectPublicKeyInfo
    }

    private class DerReader(private val buf: ByteArray, private var pos: Int = 0, private val end: Int = buf.size) {

        private fun byte(): Int = buf[pos++].toInt() and 0xFF

        private fun length(): Int {
            val first = byte()
            if (first and 0x80 == 0) return first
            var value = 0
            repeat(first and 0x7F) { value = (value shl 8) or byte() }
            return value
        }

        /** Reads the next element and returns a reader limited to its content. */
        fun readAny(): DerReader {
            pos++ // tag
            val len = length()
            val r = DerReader(buf, pos, pos + len)
            pos += len
            return r
        }

        /** Reads the next element and returns its full encoded bytes. */
        fun readElementBytes(): ByteArray {
            val start = pos
            pos++
            val len = length()
            pos += len
            return buf.copyOfRange(start, pos)
        }

        fun readSequence(): DerReader {
            pos++ // SEQUENCE tag
            val len = length()
            val r = DerReader(buf, pos, pos + len)
            pos += len
            return r
        }

        fun readExplicitTag(tag: Int) {
            if (pos < end && (buf[pos].toInt() and 0xFF) == tag) readAny()
        }
    }

    // --------------------------------------------------------------- helpers

    private fun randomBytes(length: Int): ByteArray {
        val bytes = ByteArray(length)
        SecureRandom().nextBytes(bytes)
        return bytes
    }

    /** Scene version: `(now + 24h)` expressed in 100 ns ticks, as sent by the OTA app. */
    private fun sceneVersion(): Long =
        (System.currentTimeMillis() + 24L * 3600_000L) * 10_000L

    private fun rsaWrap(publicKey: RSAPublicKey, key: ByteArray): String {
        val cipher = Cipher.getInstance("RSA/ECB/OAEPWithSHA-1AndMGF1Padding")
        cipher.init(Cipher.ENCRYPT_MODE, publicKey)
        return Base64.getEncoder().encodeToString(cipher.doFinal(key))
    }

    private fun aesCtr(data: ByteArray, key: ByteArray, iv: ByteArray, mode: Int): ByteArray {
        val cipher = Cipher.getInstance("AES/CTR/NoPadding")
        cipher.init(mode, SecretKeySpec(key, "AES"), IvParameterSpec(iv))
        return cipher.doFinal(data)
    }

    private fun ensureOtaVersion(otaVersion: String): String {
        val partsUnd = otaVersion.split("_")
        val partsDot = otaVersion.split(".")
        return if (partsUnd.size < 3 || partsDot.size < 3) {
            "$otaVersion.01_0001_197001010000"
        } else {
            otaVersion
        }
    }

    private fun defaultDeviceId(): String = DEFAULT_DEVICE_ID

    // -------------------------------------------------------------- request

    private val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()

    /**
     * The request body is identical for every device: an empty `components` array makes the server
     * answer with the full (non-incremental) package, and the local security-patch / duid values are
     * intentionally omitted so the request stays model-agnostic.
     */
    private fun buildBodyJson(args: QueryUpdateArgs): String {
        val components: JsonArray = Json.parseToJsonElement("[]").jsonArray

        return buildJsonObject {
            put("components", components)
            put("mode", BODY_MODE)
            put("time", System.currentTimeMillis())
            put("isSuportPki", false)
            put("isRooted", "0")
            put("isLocked", false)
            put("type", BODY_TYPE)
            put("registrationId", "")
            put("securityPatch", "")
            put("securityPatchVendor", "")
            put("strategyVersion", STRATEGY_VERSION)
            put("customizeName", "")
            put("customizeType", "")
            put("supportDLTaste", "1")
            put("cota", buildJsonObject {
                put("cotaVersion", "")
                put("cotaVersionName", "")
                put("buildType", "user")
            })
            put("opex", buildJsonObject { put("check", false) })
            put("sota", buildJsonObject {
                put("sotaProtocolVersion", "2")
                put("sotaProtocolVersionNew", Json.parseToJsonElement("""["apk","opex","rus","apex"]"""))
                put("sotaVersion", "new")
                put("otaUpdateTime", -1)
                put("updateViaReboot", 2)
                put("frameworkVer", "10")
                put("supportLightH", "1")
            })
            put("otaAppVersion", 0)
            put("deviceId", args.deviceId.ifBlank { defaultDeviceId() })
            put("duid", "")
            put("h5LinkVersion", 7)
            put("canUpgVer", "svt")
            put("cacheVersion", buildJsonObject {
                put("id", "")
                put("otaVersion", "")
                put("checkOtaVersionTime", -1)
                put("sotaVersion", "")
                put("checkSotaVersionTime", -1)
                put("tasteSotaVersion", "")
            })
        }.toString()
    }

    /**
     * Headers are deliberately model-agnostic. The server resolves the target from `otaVersion` +
     * `model`; sending the *local* device's `romVersion` / `prjNum` / `marketName` / `brand` / ... for
     * a different model makes the request self-contradictory and returns `2004`
     * (`artifactV1Result is empty`).
     */
    private fun buildHeaders(
        args: QueryUpdateArgs,
        cfg: Config,
        cert: CertInfo,
        protectedKey: String,
    ): Map<String, String> {
        val deviceId = args.deviceId.ifBlank { defaultDeviceId() }
        val model = args.model.ifBlank { args.otaVersion.split("_").first() }
        val cipherInfo = buildJsonObject {
            put(BIZ, buildJsonObject {
                put("protectedKey", protectedKey)
                put("version", sceneVersion())
                put("certVersion", cert.certVersion)
            })
        }.toString()

        return buildMap {
            put("language", cfg.language)
            put("newLanguage", cfg.language)
            put("romVersion", UNKNOWN)
            put("androidVersion", UNKNOWN)
            put("colorOSVersion", UNKNOWN)
            put("infVersion", INF_VERSION)
            put("otaVersion", args.otaVersion)
            put("model", model)
            put("mode", args.reqMode)
            put("nvCarrier", args.nvCarrier.ifBlank { cfg.carrierID })
            put("pipelineKey", PIPELINE_KEY)
            put("operator", PIPELINE_KEY)
            put("companyId", "")
            put("rsaVersion", RSA_VERSION)
            put("version", PROTOCOL_VERSION)
            put("deviceId", deviceId)
            put("x-otci-deviceId", deviceId)
            put("x-otci-cipherInfo", cipherInfo)
        }
    }

    private fun buildClient(proxy: String): OkHttpClient {
        val builder = OkHttpClient.Builder()
        if (proxy.isNotBlank()) {
            runCatching {
                val url = URL(proxy)
                builder.proxy(
                    Proxy(
                        Proxy.Type.HTTP,
                        InetSocketAddress(url.host, if (url.port > 0) url.port else 80)
                    )
                )
            }.onFailure { println("[WARN] proxy parse failed: ${it.message}") }
        }
        return builder.build()
    }

    private fun queryUpdateV6(args0: QueryUpdateArgs): ResponseResult {
        val result = ResponseResult()
        return try {
            val args = args0.copy()
            args.otaVersion = ensureOtaVersion(args.otaVersion)
            if (args.region.isBlank()) args.region = Regions.CN
            if (args.model.isBlank()) args.model = args.otaVersion.split("_").first()
            if (args.reqMode.isBlank()) args.reqMode = "manual"

            val cfg = getConfig(args.region)
            if (args.nvCarrier.isBlank()) args.nvCarrier = cfg.carrierID

            val client = buildClient(args.proxy)
            val cert = fetchCert(client, cfg.host)

            val key = randomBytes(AES_KEY_BYTES)
            val iv = randomBytes(AES_IV_BYTES)
            val protectedKey = rsaWrap(cert.publicKey, key)

            val plain = buildBodyJson(args).toByteArray(StandardCharsets.UTF_8)
            val envelope = buildJsonObject {
                put("cipher", Base64.getEncoder().encodeToString(aesCtr(plain, key, iv, Cipher.ENCRYPT_MODE)))
                put("iv", Base64.getEncoder().encodeToString(iv))
            }.toString()
            val requestBody = buildJsonObject { put("params", envelope) }.toString()

            val headers = buildHeaders(args, cfg, cert, protectedKey)
            val builder = Request.Builder()
                .url("https://${cfg.host}$ENDPOINT_UPDATE")
                .post(requestBody.toRequestBody(JSON_MEDIA_TYPE))
            headers.forEach { (k, v) -> builder.header(k, v) }

            val raw = client.newCall(builder.build()).execute().use { response ->
                response.body?.string().orEmpty()
            }

            val root = Json.parseToJsonElement(raw).jsonObject
            result.responseCode = root["responseCode"]?.jsonPrimitive?.contentOrNull?.toLongOrNull() ?: 0L
            result.errMsg = root["errMsg"]?.jsonPrimitive?.contentOrNull.orEmpty()

            val inner = root["body"]?.let { element ->
                when (element) {
                    is kotlinx.serialization.json.JsonPrimitive -> element.contentOrNull
                    else -> element.toString()
                }
            }
            result.body = inner

            if (!inner.isNullOrBlank()) {
                val obj = runCatching { Json.parseToJsonElement(inner).jsonObject }.getOrNull()
                val cipher = obj?.get("cipher")?.jsonPrimitive?.contentOrNull.orEmpty()
                val respIv = obj?.get("iv")?.jsonPrimitive?.contentOrNull.orEmpty()
                if (cipher.isNotEmpty() && respIv.isNotEmpty()) {
                    val plainResp = aesCtr(
                        Base64.getDecoder().decode(cipher),
                        key,
                        Base64.getDecoder().decode(respIv),
                        Cipher.DECRYPT_MODE,
                    )
                    result.decryptedBodyBytes = plainResp
                }
            }
            result
        } catch (e: Exception) {
            result.responseCode = -1
            result.errMsg = e.message ?: e.toString()
            result
        }
    }
}
