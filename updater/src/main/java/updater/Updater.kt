package updater

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.math.BigInteger
import java.net.InetSocketAddress
import java.net.Proxy
import java.net.URL
import java.nio.charset.StandardCharsets
import java.security.KeyFactory
import java.security.MessageDigest
import java.security.SecureRandom
import java.security.interfaces.RSAPublicKey
import java.security.spec.RSAPublicKeySpec
import java.security.spec.X509EncodedKeySpec
import java.util.*
import javax.crypto.Cipher
import javax.crypto.spec.SecretKeySpec
import javax.crypto.spec.IvParameterSpec

object Updater {

    private const val DEFAULT_DEVICE_ID = "0000000000000000000000000000000000000000000000000000000000000000"
    private val LEGACY_KEYS = listOf(
        "oppo1997", "baed2017", "java7865", "231uiedn", "09e32ji6",
        "0oiu3jdy", "0pej387l", "2dkliuyt", "20odiuye", "87j3id7w"
    )

    fun getConfig(region: String, gray: Int): Config = resolveConfig(region, gray)

    private fun ensureOtaVersion(otaVersion: String): String {
        val partsUnd = otaVersion.split("_")
        val partsDot = otaVersion.split(".")
        return if (partsUnd.size < 3 || partsDot.size < 3) {
            otaVersion + ".01_0001_197001010000"
        } else otaVersion
    }

    private fun ensureLegacyOtaVersion(otaVersion: String): String {
        val version = otaVersion.trim()
        return if (Regex("""^[^_]+_\d+\.[A-Z]$""").matches(version)) {
            "$version.00_0000_000000000000"
        } else version
    }

    private fun defaultDeviceId(): String = DEFAULT_DEVICE_ID

    private fun legacyDefaultDeviceId(): String =
        MessageDigest.getInstance("SHA-256")
            .digest("000000000000000".toByteArray(StandardCharsets.UTF_8))
            .joinToString("") { "%02X".format(it) }

    private fun hasCustomGuid(guid: String): Boolean {
        return guid.ifBlank { DEFAULT_DEVICE_ID }.lowercase() != DEFAULT_DEVICE_ID
    }

    private fun protectedVersion(): String {
        val ts = (System.currentTimeMillis() + 24L * 3600_000) * 1_000_000
        return ts.toString()
    }

    private fun parseRsaPublicKey(pem: String): RSAPublicKey {
        // Accept both PKCS#1 (RSA PUBLIC KEY) and X.509 SPKI (PUBLIC KEY)
        val stripped = pem
            .replace("-----BEGIN RSA PUBLIC KEY-----", "")
            .replace("-----END RSA PUBLIC KEY-----", "")
            .replace("-----BEGIN PUBLIC KEY-----", "")
            .replace("-----END PUBLIC KEY-----", "")
            .replace("\n", "")
            .replace("\r", "")
            .trim()
        val der = Base64.getDecoder().decode(stripped)

        // First try X.509 SubjectPublicKeyInfo
        runCatching {
            val kf = KeyFactory.getInstance("RSA")
            val pub = kf.generatePublic(X509EncodedKeySpec(der)) as RSAPublicKey
            return pub
        }

        // Fallback: parse PKCS#1 RSAPublicKey = SEQUENCE { INTEGER n, INTEGER e }
        var idx = 0
        fun readByte(): Int = der[idx++].toInt() and 0xFF
        fun readLen(): Int {
            var len = readByte()
            if (len and 0x80 == 0) return len
            val num = len and 0x7F
            var v = 0
            repeat(num) { v = (v shl 8) or readByte() }
            return v
        }
        fun readTag(expected: Int) {
            val t = readByte()
            if (t != expected) throw IllegalArgumentException("Invalid DER, expected tag 0x${expected.toString(16)} got 0x${t.toString(16)}")
        }

        readTag(0x30) // SEQUENCE
        readLen()

        readTag(0x02) // INTEGER (modulus)
        val modLen = readLen()
        var modBytes = der.copyOfRange(idx, idx + modLen)
        idx += modLen
        if (modBytes.isNotEmpty() && modBytes[0].toInt() == 0x00) {
            modBytes = modBytes.copyOfRange(1, modBytes.size)
        }

        readTag(0x02) // INTEGER (exponent)
        val expLen = readLen()
        val expBytes = der.copyOfRange(idx, idx + expLen)
        idx += expLen

        val modulus = BigInteger(1, modBytes)
        val exponent = BigInteger(1, expBytes)
        val spec = RSAPublicKeySpec(modulus, exponent)
        return KeyFactory.getInstance("RSA").generatePublic(spec) as RSAPublicKey
    }

    private fun rsaOaepEncryptBase64(pubKeyPem: String, data: ByteArray): String {
        val publicKey = parseRsaPublicKey(pubKeyPem)
        val cipher = Cipher.getInstance("RSA/ECB/OAEPWithSHA-1AndMGF1Padding")
        cipher.init(Cipher.ENCRYPT_MODE, publicKey)
        val out = cipher.doFinal(data)
        return Base64.getEncoder().encodeToString(out)
    }

    private fun randomBytes(len: Int): ByteArray {
        val b = ByteArray(len)
        SecureRandom().nextBytes(b)
        return b
    }

    private fun aesCtrEncryptToBase64(plain: ByteArray, key: ByteArray, iv: ByteArray): String {
        val cipher = Cipher.getInstance("AES/CTR/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"), IvParameterSpec(iv))
        val out = cipher.doFinal(plain)
        return Base64.getEncoder().encodeToString(out)
    }

    private fun aesCtrDecrypt(cipherB64: String, key: ByteArray, ivB64: String): ByteArray {
        val cipherBytes = Base64.getDecoder().decode(cipherB64)
        val iv = Base64.getDecoder().decode(ivB64)
        val cipher = Cipher.getInstance("AES/CTR/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), IvParameterSpec(iv))
        return cipher.doFinal(cipherBytes)
    }

    private fun legacyKeyFromPseudo(keyPseudo: String): ByteArray {
        val prefix = LEGACY_KEYS[keyPseudo.first().digitToInt()] + keyPseudo.substring(4, 12)
        return prefix.toByteArray(StandardCharsets.UTF_8)
    }

    private fun legacyEcbEncrypt(plain: ByteArray, key: ByteArray): ByteArray {
        val cipher = Cipher.getInstance("AES/ECB/PKCS5Padding")
        cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"))
        return cipher.doFinal(plain)
    }

    private fun legacyEcbDecrypt(cipherBytes: ByteArray, key: ByteArray): ByteArray {
        val cipher = Cipher.getInstance("AES/ECB/PKCS5Padding")
        cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"))
        return cipher.doFinal(cipherBytes)
    }

    private fun legacyCtrTransform(data: ByteArray, key: ByteArray, iv: ByteArray, mode: Int): ByteArray {
        val cipher = Cipher.getInstance("AES/CTR/NoPadding")
        cipher.init(mode, SecretKeySpec(key, "AES"), IvParameterSpec(iv))
        return cipher.doFinal(data)
    }

    private fun legacyMd5(bytes: ByteArray): ByteArray = MessageDigest.getInstance("MD5").digest(bytes)

    private fun legacyEncryptEcbString(plain: String): String {
        val charset = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789"
        val pseudo = buildString {
            append((0..9).random())
            repeat(14) { append(charset.random()) }
        }
        val key = legacyKeyFromPseudo(pseudo)
        val encrypted = legacyEcbEncrypt(plain.toByteArray(StandardCharsets.UTF_8), key)
        return Base64.getEncoder().encodeToString(encrypted) + pseudo
    }

    private fun legacyDecryptEcbString(cipherText: String): String {
        val pseudo = cipherText.takeLast(15)
        val key = legacyKeyFromPseudo(pseudo)
        val data = Base64.getDecoder().decode(cipherText.dropLast(15))
        return legacyEcbDecrypt(data, key).toString(StandardCharsets.UTF_8)
    }

    private fun legacyDecryptCtrString(cipherText: String): String {
        val pseudo = cipherText.takeLast(15)
        val key = legacyKeyFromPseudo(pseudo)
        val data = Base64.getDecoder().decode(cipherText.dropLast(15))
        val decrypted = legacyCtrTransform(data, key, legacyMd5(key), Cipher.DECRYPT_MODE)
        return decrypted.toString(StandardCharsets.UTF_8)
    }

    private fun shouldTryLegacy(args: QueryUpdateArgs, result: ResponseResult): Boolean {
        if (hasCustomGuid(args.guid)) return false
        if (args.region !in setOf(Regions.CN, Regions.EU, Regions.IN, Regions.GL)) return false
        val version = args.otaVersion.uppercase(Locale.ROOT)
        if (!Regex(""".+_11\.[A-Z].*""").matches(version)) return false
        return result.responseCode in setOf(304L, 2004L, 2100L)
    }

    private fun legacyFallbackArgs(args: QueryUpdateArgs): QueryUpdateArgs {
        return args.copy(
            gray = 0,
            reqMode = "manual"
        )
    }

    private fun legacyBaseUrl(region: String): String {
        return when (region.uppercase(Locale.ROOT)) {
            Regions.CN -> "https://iota.coloros.com/post/Query_Update"
            Regions.IN -> "https://ifota-in.realmemobile.com/post/Query_Update"
            Regions.EU -> "https://ifota-eu.realmemobile.com/post/Query_Update"
            else -> "https://ifota.realmemobile.com/post/Query_Update"
        }
    }

    private fun legacyRegionMark(region: String): String {
        return when (region.uppercase(Locale.ROOT)) {
            Regions.CN -> "CN"
            Regions.IN -> "IN"
            Regions.EU -> "EU"
            else -> "GL"
        }
    }

    private fun legacyLanguage(region: String): String {
        return if (region.equals(Regions.CN, ignoreCase = true)) "zh-CN" else "en-EN"
    }

    private fun sanitizeLegacyPacketName(packetName: String?): String {
        val name = packetName?.trim().orEmpty()
        if (name.isBlank()) return "Full OTA"
        return name.replace(Regex("""\.[A-Za-z0-9]{2,6}$"""), "")
    }

    private fun buildLegacyBody(args: QueryUpdateArgs): String {
        val regionMark = legacyRegionMark(args.region)
        val fullOtaVersion = ensureLegacyOtaVersion(args.otaVersion)
        val otaPrefix = fullOtaVersion.split("_").take(2).joinToString("_")
        val body = buildJsonObject {
            put("language", legacyLanguage(args.region))
            put("romVersion", otaPrefix)
            put("otaVersion", fullOtaVersion)
            put("androidVersion", "Android10.0")
            put("colorOSVersion", "ColorOS7")
            put("model", args.model)
            put("productName", args.model)
            put("operator", "unknown")
            put("uRegion", regionMark)
            put("trackRegion", regionMark)
            put("imei", "000000000000000")
            put("imei1", "000000000000000")
            put("mode", "0")
            put("registrationId", "unknown")
            put("deviceId", legacyDefaultDeviceId())
            put("version", "2")
            put("type", "1")
            put("otaPrefix", otaPrefix)
            put("isRealme", if (args.model.startsWith("RMX")) "1" else "0")
            put("time", System.currentTimeMillis())
            put("canCheckSelf", "0")
        }
        return buildJsonObject {
            put("params", legacyEncryptEcbString(body.toString()))
        }.toString()
    }

    private fun normalizeLegacyResponse(legacyJson: kotlinx.serialization.json.JsonObject): ByteArray {
        val downloadUrl = legacyJson["active_url"]?.jsonPrimitive?.contentOrNull
            ?: legacyJson["down_url"]?.jsonPrimitive?.contentOrNull
        val panelUrl = legacyJson["description"]?.jsonPrimitive?.contentOrNull
        val md5 = legacyJson["patch_md5"]?.jsonPrimitive?.contentOrNull
        val size = legacyJson["patch_size"]?.jsonPrimitive?.contentOrNull
        val packetName = sanitizeLegacyPacketName(legacyJson["patch_name"]?.jsonPrimitive?.contentOrNull)
        val publishedTime = legacyJson["publishedTime"]?.jsonPrimitive?.contentOrNull?.toLongOrNull()
        val androidVersion = legacyJson["newAndroidVersion"]?.jsonPrimitive?.contentOrNull
        val osVersion = legacyJson["newColorOSVersion"]?.jsonPrimitive?.contentOrNull
            ?: legacyJson["osVersion"]?.jsonPrimitive?.contentOrNull
        val otaVersion = legacyJson["otaVersion"]?.jsonPrimitive?.contentOrNull
            ?: legacyJson["new_version"]?.jsonPrimitive?.contentOrNull
        val versionName = legacyJson["versionName"]?.jsonPrimitive?.contentOrNull
            ?: legacyJson["version_name"]?.jsonPrimitive?.contentOrNull
        val status = legacyJson["status"]?.jsonPrimitive?.contentOrNull
        val googlePatch = legacyJson["googlePatchLevel"]?.jsonPrimitive?.contentOrNull?.takeIf { it != "0" }

        val normalized = buildJsonObject {
            if (status != null) put("status", status)
            if (versionName != null) {
                put("versionName", versionName)
                put("realVersionName", versionName)
            }
            if (otaVersion != null) {
                put("otaVersion", otaVersion)
                put("realOtaVersion", otaVersion)
            }
            if (androidVersion != null) {
                put("androidVersion", androidVersion)
                put("realAndroidVersion", androidVersion)
            }
            if (osVersion != null) {
                put("colorOSVersion", osVersion)
                put("osVersion", osVersion)
                put("realOsVersion", osVersion)
            }
            if (publishedTime != null) put("publishedTime", publishedTime)
            if (googlePatch != null) put("securityPatch", googlePatch)
            if (panelUrl != null) {
                put("description", buildJsonObject {
                    put("panelUrl", panelUrl)
                })
            }
            if (downloadUrl != null) {
                put("components", JsonArray(listOf(buildJsonObject {
                    put("componentName", packetName)
                    put("componentVersion", otaVersion ?: versionName ?: "")
                    put("componentPackets", buildJsonObject {
                        put("manualUrl", downloadUrl)
                        put("url", legacyJson["down_url"]?.jsonPrimitive?.contentOrNull ?: downloadUrl)
                        if (md5 != null) put("md5", md5)
                        if (size != null) put("size", size)
                    })
                })))
            }
        }
        return normalized.toString().toByteArray(StandardCharsets.UTF_8)
    }

    private fun queryLegacyUpdate(args0: QueryUpdateArgs): ResponseResult {
        val args = args0.copy()
        val result = ResponseResult()
        return try {
            if (args.model.isBlank()) args.model = args.otaVersion.split("_").first()
            if (args.nvCarrier.isBlank()) args.nvCarrier = getConfig(args.region, 0).carrierID
            args.otaVersion = ensureLegacyOtaVersion(args.otaVersion)
            val otaPrefix = args.otaVersion.split("_").take(2).joinToString("_")

            val headers = mapOf(
                "language" to legacyLanguage(args.region),
                "romVersion" to otaPrefix,
                "otaVersion" to args.otaVersion,
                "androidVersion" to "Android10.0",
                "colorOSVersion" to "ColorOS7",
                "model" to args.model,
                "infVersion" to "1",
                "operator" to "unknown",
                "nvCarrier" to args.nvCarrier,
                "uRegion" to legacyRegionMark(args.region),
                "trackRegion" to legacyRegionMark(args.region),
                "imei" to "000000000000000",
                "imei1" to "000000000000000",
                "deviceId" to legacyDefaultDeviceId(),
                "mode" to "client_auto",
                "channel" to "pc",
                "version" to "1",
                "Accept" to "application/json",
                "Content-Type" to "application/json",
                "User-Agent" to "NULL"
            )

            val clientBuilder = OkHttpClient.Builder()
            if (args.proxy.isNotBlank()) {
                runCatching {
                    val u = URL(args.proxy)
                    clientBuilder.proxy(Proxy(Proxy.Type.HTTP, InetSocketAddress(u.host, if (u.port > 0) u.port else 80)))
                }
            }
            val client = clientBuilder.build()

            val request = Request.Builder()
                .url(legacyBaseUrl(args.region))
                .headers(okhttp3.Headers.headersOf(*headers.flatMap { listOf(it.key, it.value) }.toTypedArray()))
                .post(buildLegacyBody(args).toRequestBody("application/json".toMediaType()))
                .build()

            client.newCall(request).execute().use { response ->
                val raw = response.body?.string().orEmpty()
                val json = Json.parseToJsonElement(raw).jsonObject
                result.responseCode = json["responseCode"]?.jsonPrimitive?.contentOrNull?.toLongOrNull() ?: if (json["resps"] != null) 200L else 0L
                result.errMsg = json["errMsg"]?.jsonPrimitive?.contentOrNull.orEmpty()
                result.body = raw

                val encrypted = json["resps"]?.jsonPrimitive?.contentOrNull ?: return result
                val decrypted = legacyDecryptEcbString(encrypted)
                val legacyBody = Json.parseToJsonElement(decrypted).jsonObject
                val normalized = normalizeLegacyResponse(legacyBody)
                result.decryptedBodyBytes = normalized
                result.responseCode = 200L
                result.errMsg = ""
                result.body = normalized.toString(StandardCharsets.UTF_8)
            }
            result
        } catch (e: Exception) {
            result.responseCode = -1
            result.errMsg = e.message ?: e.toString()
            result
        }
    }

    private fun selectEndpoints(args: QueryUpdateArgs): List<String> {
        val reqMode = args.reqMode.ifBlank { "manual" }.lowercase(Locale.ROOT)
        return when {
            hasCustomGuid(args.guid) -> listOf("/update/v6", "/update/v5")
            reqMode == "taste" -> listOf("/update/v3", "/update/v5")
            else -> listOf("/update/v5", "/update/v3")
        }
    }

    fun queryUpdate(args0: QueryUpdateArgs): ResponseResult {
        val result = if (args0.gray == 1 && args0.reqMode.equals("taste", ignoreCase = true) && args0.region.equals(Regions.CN, ignoreCase = true)) {
            val tasteArgs = args0.copy(gray = 0)
            val tasteResult = queryUpdateDirect(tasteArgs)
            if (tasteResult.responseCode == 200L) {
                val grayOtaVersion = extractReturnedOtaVersion(tasteResult)
                if (grayOtaVersion != null) {
                    val grayArgs = args0.copy(
                        otaVersion = grayOtaVersion,
                        gray = 1,
                        reqMode = "manual"
                    )
                    queryUpdateDirect(grayArgs)
                } else {
                    tasteResult
                }
            } else {
                tasteResult
            }
        } else {
            queryUpdateDirect(args0)
        }

        if (shouldTryLegacy(args0, result)) {
            val legacyResult = queryLegacyUpdate(legacyFallbackArgs(args0))
            if (legacyResult.responseCode == 200L) {
                return legacyResult
            }
        }
        return result
    }

    private fun extractReturnedOtaVersion(result: ResponseResult): String? {
        if (result.decryptedBodyBytes.isEmpty()) return null
        val body = runCatching {
            Json.parseToJsonElement(result.decryptedBodyBytes.decodeToString()).jsonObject
        }.getOrNull() ?: return null

        return body["realOtaVersion"]?.jsonPrimitive?.contentOrNull
            ?: body["otaVersion"]?.jsonPrimitive?.contentOrNull
    }

    private fun queryUpdateDirect(args0: QueryUpdateArgs): ResponseResult {
        val result = ResponseResult()
        val tStart = System.currentTimeMillis()
        println("[DEBUG] queryUpdate() start, args=$args0")

        return try {
            val args = args0.copy()
            args.otaVersion = ensureOtaVersion(args.otaVersion)
            if (args.region.isBlank()) args.region = Regions.CN
            if (args.model.isBlank()) args.model = args.otaVersion.split("_").first()

            val cfg = getConfig(args.region, args.gray)
            if (args.nvCarrier.isBlank()) args.nvCarrier = cfg.carrierID
            println("[DEBUG] cfg.host=${cfg.host}, carrier=${args.nvCarrier}, pubKeyVer=${cfg.publicKeyVersion}")

            val iv = randomBytes(16)
            val key = randomBytes(32)

            val protectedKey = rsaOaepEncryptBase64(
                cfg.publicKey,
                Base64.getEncoder().encode(key)
            )
            println("[DEBUG] protectedKey len=${protectedKey.length}")

            val deviceId = defaultDeviceId()
            val guid = if (args.guid.isNotBlank()) args.guid.lowercase() else defaultDeviceId()
            val reqMode = args.reqMode.ifBlank { "manual" }

            val headers = mutableMapOf(
                "language" to cfg.language,
                "androidVersion" to "unknown",
                "colorOSVersion" to "unknown",
                "romVersion" to "unknown",
                "otaVersion" to args.otaVersion,
                "model" to args.model,
                "mode" to reqMode,
                "nvCarrier" to args.nvCarrier,
                "infVersion" to "1",
                "version" to cfg.version,
                "deviceId" to deviceId,
                "Content-Type" to "application/json; charset=utf-8",
            )

            val pkJson = buildJsonObject {
                put("SCENE_1", buildJsonObject {
                    put("protectedKey", protectedKey)
                    put("version", protectedVersion())
                    put("negotiationVersion", cfg.publicKeyVersion)
                })
            }.toString()
            headers["protectedKey"] = pkJson
            println("[DEBUG] headers keys=${headers.keys}")

            val bodyPlain = buildJsonObject {
                put("mode", "0")
                put("time", System.currentTimeMillis())
                put("isRooted", "0")
                put("isLocked", true)
                put("type", "0")
                put("deviceId", guid)
            }.toString().toByteArray(StandardCharsets.UTF_8)

            val cipherB64 = aesCtrEncryptToBase64(bodyPlain, key, iv)
            val reqBody = buildJsonObject {
                put("cipher", cipherB64)
                put("iv", Base64.getEncoder().encodeToString(iv))
            }.toString()

            val clientBuilder = OkHttpClient.Builder()
            if (args.proxy.isNotBlank()) {
                runCatching {
                    val u = URL(args.proxy)
                    val proxy = Proxy(
                        Proxy.Type.HTTP,
                        InetSocketAddress(u.host, if (u.port > 0) u.port else 80)
                    )
                    clientBuilder.proxy(proxy)
                    println("[DEBUG] use proxy=${u.host}:${if (u.port > 0) u.port else 80}")
                }.onFailure { e ->
                    println("[WARN] proxy parse failed: ${e.message}")
                }
            }
            val client = clientBuilder.build()

            var body = ""
            var requestError: Exception? = null
            val endpoints = selectEndpoints(args)
            println("[DEBUG] request endpoints=$endpoints")

            for (endpoint in endpoints) {
                val url = URL("https", cfg.host, endpoint).toString()
                println("[DEBUG] request url=$url")

                val request = Request.Builder()
                    .url(url)
                    .headers(
                        okhttp3.Headers.headersOf(*headers.flatMap { listOf(it.key, it.value) }.toTypedArray())
                    )
                    .post(Json.encodeToString(mapOf("params" to reqBody)).toRequestBody("application/json; charset=utf-8".toMediaType()))
                    .build()

                val tReq = System.currentTimeMillis()
                runCatching {
                    client.newCall(request).execute().use { response ->
                        body = response.body?.string().orEmpty()
                        println("[DEBUG] http code=${response.code}, endpoint=$endpoint, cost=${System.currentTimeMillis() - tReq}ms, bodyLen=${body.length}")
                    }
                }.onFailure { e ->
                    requestError = e as? Exception ?: Exception(e)
                    println("[WARN] request failed on $endpoint: ${e.message}")
                }

                if (body.isNotBlank()) break
            }

            if (body.isBlank() && requestError != null) {
                throw requestError
            }

            val json = runCatching { Json.parseToJsonElement(body).jsonObject }
                .getOrElse { e ->
                    println("[ERROR] resp json parse error: ${e.message}")
                    result.responseCode = -1
                    result.errMsg = "resp json parse error: ${e.message}"
                    return result
                }

            result.responseCode = json["responseCode"]?.toString()?.toLongOrNull() ?: 0
            result.errMsg = json["errMsg"]?.toString()?.trim('"') ?: ""
            val bodyElem = json["body"]
            val respBodyRaw = when {
                bodyElem == null -> null
                bodyElem is kotlinx.serialization.json.JsonPrimitive -> bodyElem.contentOrNull
                bodyElem is kotlinx.serialization.json.JsonObject -> bodyElem.toString()
                else -> bodyElem.toString()
            }
            result.body = respBodyRaw

            if (!respBodyRaw.isNullOrBlank()) {
                val bodyObj = runCatching { Json.parseToJsonElement(respBodyRaw).jsonObject }
                    .getOrElse { e ->
                        println("[ERROR] inner body parse error: ${e.message}")
                        null
                    }
                if (bodyObj != null) {
                    val ivB64 = bodyObj["iv"]?.jsonPrimitive?.contentOrNull ?: ""
                    val cipher = bodyObj["cipher"]?.jsonPrimitive?.contentOrNull ?: ""
                    if (ivB64.isNotBlank() && cipher.isNotBlank()) {
                        runCatching {
                            result.decryptedBodyBytes = aesCtrDecrypt(cipher, key, ivB64)
                            println("[DEBUG] decrypt ok, bytes=${result.decryptedBodyBytes.size}")
                        }.onFailure { e ->
                            println("[ERROR] decrypt failed: ${e.message}")
                        }
                    }
                }
            }

            println("[DEBUG] queryUpdate() done, total=${System.currentTimeMillis() - tStart}ms")
            if (result.responseCode != 0L && result.errMsg.isBlank()) {
                result.errMsg = "Unknown error occurred"
            }
            result
        } catch (e: Exception) {
            println("[ERROR] queryUpdate() failed: ${e.message}")
            result.responseCode = -1
            result.errMsg = e.message ?: e.toString()
            result
        }
    }
}
