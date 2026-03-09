package updater

data class QueryUpdateArgs(
    var otaVersion: String = "",
    var region: String = "CN",
    var model: String = "",
    var nvCarrier: String = "",
    var guid: String = "",
    var proxy: String = "",
    var gray: Int = 0,
    var reqMode: String = "manual"
)
