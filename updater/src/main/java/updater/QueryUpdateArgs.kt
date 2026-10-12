package updater

/**
 * Query parameters for `/update/v6`.
 *
 * Only the fields that describe **what to look up** are exposed. Everything that merely describes
 * the *local* device state (`romVersion`, `androidVersion`, `colorOSVersion`, `prjNum`,
 * `marketName`, `brand`, `osType`, ...) is deliberately **not** sent: the server resolves the
 * target purely from [otaVersion] + [model] (+ region/carrier), and mixing in the local device's
 * values makes the request self-contradictory and yields `2004` for any other model.
 */
data class QueryUpdateArgs(
    /** Full OTA prefix, e.g. `PLK110_11.A` or `RMX5010_11.H.21_3210_202610062344`. */
    var otaVersion: String = "",
    /** Server region: CN (default), EU, IN, SG, RU, TR, TH, GL. */
    var region: String = "CN",
    /** Device model, e.g. `PLK110`. Defaults to the prefix of [otaVersion]. */
    var model: String = "",
    /** NV carrier id, e.g. `10010111`. Defaults to the region carrier id. */
    var nvCarrier: String = "",
    /** 64 hex char device id. Defaults to 64 zeros. */
    var deviceId: String = "",
    /** HTTP proxy, e.g. `http://127.0.0.1:7890`. */
    var proxy: String = "",
    /** Channel: `manual` (default), `client_auto`, `server_auto` or `taste`. */
    var reqMode: String = "manual",
)
