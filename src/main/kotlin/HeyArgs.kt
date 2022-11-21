import com.xenomachina.argparser.ArgParser
import com.xenomachina.argparser.default

class HeyArgs(parser: ArgParser) {
    val concurrentCount by parser.storing(
        "-c", "--concurrent",
        help = "concurrent requests"
    ) { toInt() }.default(5)

    val number by parser.storing(
        "-n", "--number",
        help = "number of requests"
    ) { toInt() }.default(10)

    val timeout by parser.storing(
        "-t", "--timeout",
        help = "http timeout"
    ) { toLong() }.default(30L)

    val url by parser.positional(
        "URL", "URL"
    ) { toString() }.default<String?>(null)
}