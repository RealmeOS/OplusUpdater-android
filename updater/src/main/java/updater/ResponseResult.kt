package updater

class ResponseResult(
    var responseCode: Long = 0,
    var errMsg: String = "",
    var body: String? = null,
) {
    var decryptedBodyBytes: ByteArray = ByteArray(0)
}
