package updater

class Config(
    val carrierID: String,
    val host: String,
    val language: String,
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

private const val HOST_CN = "component-ota-cn.allawntech.com"
private const val HOST_EU = "component-ota-eu.allawnos.com"
private const val HOST_IN = "component-ota-in.allawnos.com"
private const val HOST_SG = "component-ota-sg.allawnos.com"

fun resolveConfig(region: String): Config = when (region.uppercase()) {
    Regions.EU -> Config(carrierID = "01000100", host = HOST_EU, language = "en-GB")
    Regions.IN -> Config(carrierID = "00011011", host = HOST_IN, language = "en-IN")
    Regions.SG -> Config(carrierID = "01011010", host = HOST_SG, language = "en-SG")
    Regions.RU -> Config(carrierID = "00110111", host = HOST_SG, language = "ru-RU")
    Regions.TR -> Config(carrierID = "01010001", host = HOST_SG, language = "tr-TR")
    Regions.TH -> Config(carrierID = "00111001", host = HOST_SG, language = "th-TH")
    Regions.GL -> Config(carrierID = "10100111", host = HOST_SG, language = "en-US")
    else -> Config(carrierID = "10010111", host = HOST_CN, language = "zh-CN")
}
