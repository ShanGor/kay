import kotlinx.coroutines.*
import org.junit.jupiter.api.Test
import java.math.BigInteger
import java.nio.file.Files
import kotlin.random.Random


open class Tests {
    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun testCoroutine() {
        newFixedThreadPoolContext(1, "name").use { ctx ->
            runBlocking(ctx.limitedParallelism(1)) {
                for (i in 1..100) {
                    launch {

                        delay(Random.nextInt(from=1, until = 100).toLong())
                        println("current is ${fact(i.toBigInteger())}")
                    }
                }
            }
        }

    }

    private fun fact(n: BigInteger): BigInteger {
        return if (n <= BigInteger.TWO)
            n
        else
            n * fact(n - BigInteger.ONE)
    }

    @Test
    fun testFact() {
        println(fact(BigInteger.valueOf(4L)))
        println(fact(BigInteger.valueOf(5)))
        println(fact(BigInteger.valueOf(100)))
    }
}