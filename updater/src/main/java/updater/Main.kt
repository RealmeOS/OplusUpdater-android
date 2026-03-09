package updater

import kotlin.system.exitProcess
import kotlinx.serialization.json.Json

fun main(args: Array<String>) {
    try {
        val parsedArgs = parseArgs(args)
        val result = Updater.queryUpdate(parsedArgs)
        
        // 显示响应状态
        println("Response Code: ${result.responseCode}")
        if (result.errMsg.isNotBlank()) {
            println("Error Message: ${result.errMsg}")
        }
        
        // 即使响应码不为0也显示响应体，因为可能包含有用的信息
        
        // 然后显示响应体内容
        try {
            val jsonString = if (result.decryptedBodyBytes.isNotEmpty()) {
                String(result.decryptedBodyBytes, Charsets.UTF_8)
            } else if (result.body != null) {
                result.body!!
            } else {
                null
            }
            
            if (jsonString != null) {
                val json = Json.parseToJsonElement(jsonString)
                println("\nResponse Body:")
                println(Json { prettyPrint = true }.encodeToString(json))
            } else {
                println("\nNo response body received")
            }
        } catch (e: Exception) {
            println("\nFailed to parse response body:")
            if (result.decryptedBodyBytes.isNotEmpty()) {
                println(String(result.decryptedBodyBytes, Charsets.UTF_8))
            } else if (result.body != null) {
                println(result.body)
            }
        }
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
    
    var otaVersion = args[0]
    var model = ""
    var region = "CN"
    var nvCarrier = ""
    var guid = ""
    var proxy = ""
    var gray = 0
    var reqMode = "manual"
    
    var i = 1
    while (i < args.size) {
        val arg = args[i]
        
        // 处理 --key=value 格式的参数
        if (arg.contains("=")) {
            val parts = arg.split("=", limit = 2)
            val key = parts[0]
            val value = parts[1]
            
            when (key) {
                "--model", "-m" -> model = value
                "--region", "-r" -> region = value
                "--carrier", "-c" -> nvCarrier = value
                "--guid", "-g" -> guid = value
                "--proxy", "-p" -> proxy = value
                "--gray" -> gray = value.toIntOrNull() ?: 0
                "--reqmode" -> reqMode = value
                "--mode" -> {} // 忽略mode参数
                else -> {
                    System.err.println("Unknown option: $key")
                    printUsage()
                    exitProcess(1)
                }
            }
            i += 1
        } else {
            // 处理 --key value 格式的参数
            when (arg) {
                "--model", "-m" -> {
                    model = getNextArg(args, i, "model")
                    i += 2
                }
                "--region", "-r" -> {
                    region = getNextArg(args, i, "region")
                    i += 2
                }
                "--carrier", "-c" -> {
                    nvCarrier = getNextArg(args, i, "carrier")
                    i += 2
                }
                "--mode" -> {
                    // mode参数不再使用，为了兼容性保留
                    getNextIntArg(args, i, "mode")
                    i += 2
                }
                "--guid", "-g" -> {
                    guid = getNextArg(args, i, "guid")
                    i += 2
                }
                "--proxy", "-p" -> {
                    proxy = getNextArg(args, i, "proxy")
                    i += 2
                }
                "--gray" -> {
                    gray = getNextIntArg(args, i, "gray")
                    i += 2
                }
                "--reqmode" -> {
                    reqMode = getNextArg(args, i, "reqmode")
                    i += 2
                }
                "--help", "-h" -> {
                    printUsage()
                    exitProcess(0)
                }
                else -> {
                    System.err.println("Unknown option: $arg")
                    printUsage()
                    exitProcess(1)
                }
            }
        }
    }
    
    return QueryUpdateArgs(
        otaVersion = otaVersion,
        region = region,
        model = model,
        nvCarrier = nvCarrier,
        guid = guid,
        proxy = proxy,
        gray = gray,
        reqMode = reqMode
    )
}

private fun getNextArg(args: Array<String>, index: Int, flagName: String): String {
    if (index + 1 >= args.size) {
        System.err.println("Error: Missing value for $flagName")
        exitProcess(1)
    }
    return args[index + 1]
}

private fun getNextIntArg(args: Array<String>, index: Int, flagName: String): Int {
    val value = getNextArg(args, index, flagName)
    return try {
        value.toInt()
    } catch (e: NumberFormatException) {
        System.err.println("Error: $flagName must be an integer")
        exitProcess(1)
    }
}

private fun printUsage() {
    println("""
        Usage: java -har updater-all.jar [OTA_VERSION] [OPTIONS]
        
        Query OPlus, OPPO and Realme Mobile OS version updates using official API
        
        Options:
          -m, --model TEXT      Device model (required), e.g., RMX3820, CPH2401
          -r, --region TEXT     Server region: CN (default), EU or IN
          -c, --carrier TEXT    Carrier ID found in `my_manifest/build.prop` file under the `NV_ID` reference, e.g., 01000100
          --mode INT            Mode: 0 (stable, default) or 1 (testing) [DEPRECATED]
          -g, --guid TEXT       GUID, e.g., 1234567890(64 bit)
          -p, --proxy TEXT      Proxy server, e.g., type://user:password@host:port
          --gray INT            Gray update server: 0 (default) or 1 (use gray server for CN region)
          --reqmode TEXT        Request Mode: manual (default), server_auto, client_auto or taste. Do not use taste mode together with gray update mode (--gray=1).
          -h, --help            Show this help message
        
        Examples:
          java -jar updater-all.jar PLK110_11.A --model=PLK110 --carrier=10010111 --region=CN
          java -jar updater-all.jar RMX3820_13.1.0.130_0130_202404010000 --model=RMX3820 --region=IN --mode=1
          java -jar updater-all.jar A127_13.0_0001 --model=A127 --carrier=00000000 --proxy=http://localhost:7890
          java -jar updater-all.jar OPD2413_11.A --model=OPD2413 --region=CN --gray=1
          java -jar updater-all.jar PJX110_11.C --region=CN --reqmode=taste
    """.trimIndent())
}