package updater

import kotlinx.serialization.json.Json
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
import java.security.SecureRandom
import java.security.interfaces.RSAPublicKey
import java.security.spec.RSAPublicKeySpec
import java.security.spec.X509EncodedKeySpec
import java.util.*
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

object Updater {

    fun getConfig(region: String, gray: Int): Config = resolveConfig(region, gray)

    private fun ensureOtaVersion(otaVersion: String): String {
        val partsUnd = otaVersion.split("_")
        val partsDot = otaVersion.split(".")
        return if (partsUnd.size < 3 || partsDot.size < 3) {
            otaVersion + ".01_0001_197001010000"
        } else otaVersion
    }

    private fun defaultDeviceId(): String = "0".repeat(64)

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

    fun queryUpdate(args0: QueryUpdateArgs): ResponseResult {
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

            val url = URL("https", cfg.host, "/update/v5").toString()
            println("[DEBUG] request url=$url")

            val request = Request.Builder()
                .url(url)
                .headers(
                    okhttp3.Headers.headersOf(*headers.flatMap { listOf(it.key, it.value) }.toTypedArray())
                )
                .post(Json.encodeToString(mapOf("params" to reqBody)).toRequestBody("application/json; charset=utf-8".toMediaType()))
                .build()

            val tReq = System.currentTimeMillis()
            val response = client.newCall(request).execute()
            val body = response.body?.string().orEmpty()
            println("[DEBUG] http code=${response.code}, cost=${System.currentTimeMillis() - tReq}ms, bodyLen=${body.length}")

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
            
            // 如果响应码不为0，确保错误信息被设置
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