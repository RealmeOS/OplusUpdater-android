package updater

class Config(
    val carrierID: String,
    val host: String,
    val language: String,
    val publicKey: String,
    val publicKeyVersion: String,
    val version: String,
)

object Regions {
    const val CN = "CN"
    const val EU = "EU"
    const val IN = "IN"
    const val SG = "SG"
    const val RU = "RU"
    const val TR = "TR"
    const val TH = "TH"
    const val GL = "GL"
}

private const val PUBLIC_KEY_CN = """
-----BEGIN RSA PUBLIC KEY-----
MIIBIjANBgkqhkiG9w0BAQEFAAOCAQ8AMIIBCgKCAQEApXYGXQpNL7gmMzzvajHa
oZIHQQvBc2cOEhJc7/tsaO4sT0unoQnwQKfNQCuv7qC1Nu32eCLuewe9LSYhDXr9
KSBWjOcCFXVXteLO9WCaAh5hwnUoP/5/Wz0jJwBA+yqs3AaGLA9wJ0+B2lB1vLE4
FZNE7exUfwUc03fJxHG9nCLKjIZlrnAAHjRCd8mpnADwfkCEIPIGhnwq7pdkbamZ
coZfZud1+fPsELviB9u447C6bKnTU4AaMcR9Y2/uI6TJUTcgyCp+ilgU0JxemrSI
PFk3jbCbzamQ6Shkw/jDRzYoXpBRg/2QDkbq+j3ljInu0RHDfOeXf3VBfHSnQ66H
CwIDAQAB
-----END RSA PUBLIC KEY-----
"""

private const val PUBLIC_KEY_IN = """
-----BEGIN RSA PUBLIC KEY-----
MIIBIjANBgkqhkiG9w0BAQEFAAOCAQ8AMIIBCgKCAQEAwYtghkzeStC9YvAwOQmW
ylbp74Tj8hhi3f9IlK7A/CWrGbLgzz/BeKxNb45zBN8pgaaEOwAJ1qZQV5G4nPro
WCPOP1ro1PkemFJvw/vzOOT5uN0ADnHDzZkZXCU/knxqUSfLcwQlHXsYhNsAm7uO
KjY9YXF4zWzYN0eFPkML3Pj/zg7hl/ov9clB2VeyI1/blMHFfcNA/fvqDTENXcNB
IhgJvXiCpLcZqp+aLZPC5AwY/sCb3j5jTWer0Rk0ZjQBZE1AncwYvUx4mA65U59c
WpTyl4c47J29MsQ66hqWv6eBHlDNZSEsQpHePUqgsf7lmO5Wd7teB8ugQki2oz1Y
5QIDAQAB
-----END RSA PUBLIC KEY-----
"""

private const val PUBLIC_KEY_EU = """
-----BEGIN RSA PUBLIC KEY-----
MIIBIjANBgkqhkiG9w0BAQEFAAOCAQ8AMIIBCgKCAQEAh8/EThsK3f0WyyPgrtXb
/D0Xni6UZNppaQHUqHWo976cybl92VxmehE0ISObnxERaOtrlYmTPIxkVC9MMueD
vTwZ1l0KxevZVKU0sJRxNR9AFcw6D7k9fPzzpNJmhSlhpNbt3BEepdgibdRZbacF
3NWy3ejOYWHgxC+I/Vj1v7QU5gD+1OhgWeRDcwuV4nGY1ln2lvkRj8EiJYXfkSq/
wUI5AvPdNXdEqwou4FBcf6mD84G8pKDyNTQwwuk9lvFlcq4mRqgYaFg9DAgpDgqV
K4NTJWM7tQS1GZuRA6PhupfDqnQExyBFhzCefHkEhcFywNyxlPe953NWLFWwbGvF
KwIDAQAB
-----END RSA PUBLIC KEY-----
"""

private const val PUBLIC_KEY_SG = """
-----BEGIN RSA PUBLIC KEY-----
MIIBIjANBgkqhkiG9w0BAQEFAAOCAQ8AMIIBCgKCAQEAkA980wxi+eTGcFDiw2I6
RrUeO4jL/Aj3Yw4dNuW7tYt+O1sRTHgrzxPD9SrOqzz7G0KgoSfdFHe3JVLPN+U1
waK+T0HfLusVJshDaMrMiQFDUiKajb+QKr+bXQhVofH74fjat+oRJ8vjXARSpFk4
/41x5j1Bt/2bHoqtdGPcUizZ4whMwzap+hzVlZgs7BNfepo24PWPRujsN3uopl+8
u4HFpQDlQl7GdqDYDj2zNOHdFQI2UpSf0aIeKCKOpSKF72KDEESpJVQsqO4nxMwE
i2jMujQeCHyTCjBZ+W35RzwT9+0pyZv8FB3c7FYY9FdF/+lvfax5mvFEBd9jO+dp
MQIDAQAB
-----END RSA PUBLIC KEY-----
"""

private const val COMMON_HOST = "component-ota-sg.allawnos.com"

fun resolveConfig(region: String, gray: Int): Config {
    val defaultVersion = "2"

    val baseSG = Config(
        carrierID = "", // overridden below
        host = COMMON_HOST,
        language = "",
        publicKey = PUBLIC_KEY_SG,
        publicKeyVersion = "1615895993238",
        version = defaultVersion,
    )

    val overrides = mapOf(
        Regions.SG to ("01011010" to "en-SG"),
        Regions.RU to ("00110111" to "ru-RU"),
        Regions.TR to ("01010001" to "tr-TR"),
        Regions.TH to ("00111001" to "th-TH"),
        Regions.GL to ("10100111" to "en-US"),
    )
    overrides[region]?.let { (carrier, lang) ->
        return Config(
            carrierID = carrier,
            host = baseSG.host,
            language = lang,
            publicKey = baseSG.publicKey,
            publicKeyVersion = baseSG.publicKeyVersion,
            version = baseSG.version,
        )
    }

    when (region) {
        Regions.EU -> return Config(
            carrierID = "01000100",
            host = "component-ota-eu.allawnos.com",
            language = "en-GB",
            publicKey = PUBLIC_KEY_EU,
            publicKeyVersion = "1615897067573",
            version = defaultVersion,
        )
        Regions.IN -> return Config(
            carrierID = "00011011",
            host = "component-ota-in.allawnos.com",
            language = "en-IN",
            publicKey = PUBLIC_KEY_IN,
            publicKeyVersion = "1615896309308",
            version = defaultVersion,
        )
    }

    val host = if (region == Regions.CN) {
        if (gray == 1) "component-ota-gray.coloros.com" else "component-ota-cn.allawntech.com"
    } else {
        COMMON_HOST
    }

    return Config(
        carrierID = if (region == Regions.CN) "10010111" else "01011010",
        host = host,
        language = if (region == Regions.CN) "zh-CN" else "en-US",
        publicKey = if (region == Regions.CN) PUBLIC_KEY_CN else PUBLIC_KEY_SG,
        publicKeyVersion = if (region == Regions.CN) "1615879139745" else "1615895993238",
        version = defaultVersion,
    )
}
