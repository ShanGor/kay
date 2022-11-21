import com.xenomachina.argparser.ArgParser

import kotlinx.coroutines.*
import java.math.BigDecimal
import java.math.RoundingMode
import java.net.HttpURLConnection
import java.net.URI
import java.net.URL
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse.BodyHandlers
import java.time.Duration
import java.util.concurrent.*

import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.LongAdder
import kotlin.math.roundToInt

@OptIn(DelicateCoroutinesApi::class)
fun heyWithJDKBio(args: Array<String>) {
    ArgParser(args).parseInto(::HeyArgs).run {
        println("Total number of requests: $number")
        println("Concurrent: $concurrentCount")
        println("URL: $url")
        println("Http Timeout: $timeout seconds")

        val list = ConcurrentLinkedQueue<Pair<Int, Long>>()
        val queue = LinkedBlockingQueue<HttpRequest>(concurrentCount)

        val totalStartTime = System.currentTimeMillis()

        val taskCount = AtomicLong(0)

        val overflowCount = LongAdder()

        val producerLatch = CountDownLatch(number)
        val consumerLatch = CountDownLatch(number)

        repeat(number) {
            GlobalScope.launch  {
                var successful = false
                while (!successful) {
                    try {
                        queue.add(HttpRequest.newBuilder(URI.create(url))
                            .timeout(Duration.ofSeconds(timeout))
                            .GET().build())
                        successful = true
                    } catch (e: IllegalStateException) {
                        delay(1)
                    }
                }

            }.invokeOnCompletion {
                producerLatch.countDown()
            }
        }

        repeat(number) {

            GlobalScope.launch(Dispatchers.IO) {
                val startTime = System.currentTimeMillis()
                var conn:HttpURLConnection? = null
                var status: Int
                try {
                    var overflow = false
                    val currentRequest = queue.poll(1000, TimeUnit.SECONDS)
                    while(taskCount.get() >= concurrentCount) {
                        overflow = true
                        delay(1)
                    }
                    if (overflow) overflowCount.increment()

                    taskCount.incrementAndGet()
                    conn = currentRequest.uri().toURL().openConnection() as HttpURLConnection
                    conn.readTimeout = timeout.toInt() * 1000
                    conn.connectTimeout = timeout.toInt() * 1000
                    conn.requestMethod = "GET"
                    conn.connect()
                    conn.inputStream.readAllBytes()
                    status = conn.responseCode
                } catch (e: Exception) {
                    status = 504
                } finally {
                    conn?.disconnect()
                    taskCount.decrementAndGet()
                }

                val endTime = System.currentTimeMillis()
                list.add(Pair(status, endTime - startTime))
                val percentage = BigDecimal.valueOf(consumerLatch.count / number.toDouble() * 100).setScale(2, RoundingMode.HALF_UP).toPlainString()
                val totalElapsed = System.currentTimeMillis() - totalStartTime
                val etc = totalElapsed / (100 - percentage.toDouble())  / 1000 * percentage.toDouble()
                print("\r$percentage% tasks left, estimated time to complete is ${etc.roundToInt()} seconds..")
                consumerLatch.countDown()
            }

        }

        producerLatch.await()
        println("\nProducers done the job!")
        consumerLatch.await()
        println("\nConsumers done the job!")
        val totalEndTime = System.currentTimeMillis()

        val countTable = HashMap<Int, Int>()
        val maxTime = AtomicLong(0L)
        val minTime = AtomicLong(100000L)
        list.forEach { (status, milliseconds) ->
            if (countTable.containsKey(status)) {
                countTable[status] = countTable[status]!! + 1
            } else {
                countTable[status] = 1
            }
            if (status == 200 && maxTime.get() < milliseconds) {
                maxTime.set(milliseconds)
            }

            if (status == 200 && minTime.get() > milliseconds) {
                minTime.set(milliseconds)
            }
        }

        println("Total time: ${(totalEndTime - totalStartTime) / 1000.0} seconds")
        println(countTable)
        println("Max time: ${maxTime.get()/1000.0} seconds")
        println("Min time: ${minTime.get()/1000.0} seconds")

        println("Full concurrency time count: ${overflowCount.sum()}")

    }

}
