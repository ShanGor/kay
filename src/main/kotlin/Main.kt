import com.xenomachina.argparser.ArgParser

import com.xenomachina.argparser.mainBody
fun main(args: Array<String>) = mainBody {
    val parsedArgs = ArgParser(args).parseInto(::HeyArgs)
    heyWithWebClient(parsedArgs)
}
