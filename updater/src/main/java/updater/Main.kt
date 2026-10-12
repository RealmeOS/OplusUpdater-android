package updater

import kotlin.system.exitProcess
import kotlinx.serialization.json.Json

fun main(args: Array<String>) {
    try {
        val parsedArgs = parseArgs(args)
        val result = Updater.queryUpdate(parsedArgs)

        println("Response Code: ${result.responseCode}")
        if (result.errMsg.isNotBlank()) {
            println("Error Message: ${result.errMsg}")
        }

        val jsonString = when {
            result.decryptedBodyBytes.isNotEmpty() -> String(result.decryptedBodyBytes, Charsets.UTF_8)
            !result.body.isNullOrBlank() -> result.body
            else -> null
        }

        if (jsonString == null) {
            println("\nNo response body received")
            return
        }

        println("\nResponse Body:")
        runCatching {
            Json { prettyPrint = true }.encodeToString(Json.parseToJsonElement(jsonString))
        }.onSuccess { println(it) }.onFailure { println(jsonString) }
    } catch (e: Exception) {
        System.err.println("Error: ${e.message}")
        exitProcess(1)
    }
}

fun parseArgs(args: Array<String>): QueryUpdateArgs {
    if (args.isEmpty() || args[0] == "--help" || args[0] == "-h") {
        printUsage()
        exitProcess(0)
    }

    val parsed = QueryUpdateArgs(otaVersion = args[0])
    var i = 1
    while (i < args.size) {
        val arg = args[i]
        val inline = arg.contains("=")
        val key = if (inline) arg.substringBefore("=") else arg
        val value = if (inline) arg.substringAfter("=", "") else null

        fun take(): String {
            if (inline) return value!!
            if (i + 1 >= args.size) {
                System.err.println("Error: Missing value for $key")
                exitProcess(1)
            }
            return args[i + 1]
        }

        when (key) {
            "--model", "-m" -> parsed.model = take()
            "--region", "-r" -> parsed.region = take()
            "--carrier", "-c" -> parsed.nvCarrier = take()
            "--guid", "-g", "--device-id" -> parsed.deviceId = take()
            "--proxy", "-p" -> parsed.proxy = take()
            "--reqmode" -> parsed.reqMode = take()
            "--help", "-h" -> {
                printUsage()
                exitProcess(0)
            }
            else -> {
                System.err.println("Unknown option: $key")
                printUsage()
                exitProcess(1)
            }
        }
        i += if (inline) 1 else 2
    }
    return parsed
}

private fun printUsage() {
    println(
        """
        Usage: java -jar updater-all.jar [OTA_VERSION] [OPTIONS]

        Query OPlus, OPPO and realme Mobile OS updates through the official /update/v6 API.

        Options:
          -m, --model TEXT         Device model, e.g. PLK110 (default: prefix of OTA_VERSION)
          -r, --region TEXT        Server region: CN (default), EU, IN, SG, RU, TR, TH, GL
          -c, --carrier TEXT       nvCarrier, e.g. 10010111 (default: from region)
          -g, --device-id TEXT     64 hex char device id (default: 64 zeros)
          -p, --proxy TEXT         HTTP proxy, e.g. http://127.0.0.1:7890
              --reqmode TEXT       Channel: manual (default), client_auto, server_auto, taste
          -h, --help               Show this help message

        Notes:
          * OTA_VERSION must be the full prefix of the model you want to query, e.g. PLK110_11.A.
            The branch letter (A / C / F / H / J) selects the OS generation: the newest one is
            usually only served on the "taste" channel, older ones on "manual".
          * Only OTA_VERSION and its model prefix describe the target. The local device's
            romVersion / prjNum / marketName / brand / osType are never sent, so any model can be
            queried from any device.
          * responseCode 200 = update available, 2004 = none for that model/channel,
            304 = rejected by the server (e.g. "security patch has problem").

        Examples:
          java -jar updater-all.jar PLK110_11.A -m PLK110
          java -jar updater-all.jar RMX5010_11.H --reqmode taste
          java -jar updater-all.jar RMX5010_11.F --region CN
          java -jar updater-all.jar CPH2573_11.F.43_2430_202603192142 --region IN
        """.trimIndent()
    )
}
