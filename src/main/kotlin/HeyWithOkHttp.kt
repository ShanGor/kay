import com.xenomachina.argparser.ArgParser
import kotlinx.coroutines.*
import okhttp3.*
import java.io.IOException
import java.math.BigDecimal
import java.math.RoundingMode
import java.time.Duration
import java.util.concurrent.*

import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.LongAdder
import kotlin.math.roundToInt

@OptIn(DelicateCoroutinesApi::class)
fun heyWithOkHttp(args: Array<String>) {
    ArgParser(args).parseInto(::HeyArgs).run {
        println("Total number of requests: $number")
        println("Concurrent: $concurrentCount")
        println("URL: $url")
        println("Http Timeout: $timeout seconds")
        val client = OkHttpClient.Builder()
            .connectTimeout(Duration.ofSeconds(timeout))
            .callTimeout(Duration.ofSeconds(timeout)).dispatcher(Dispatcher(Executors.newFixedThreadPool(32)))
            .connectionPool(ConnectionPool())
            .build()


        val list = ConcurrentLinkedQueue<Pair<Int, Long>>()
        val queue = LinkedBlockingQueue<Request>(concurrentCount)

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


                        val request = Request.Builder().get().url(url!!).build()
                        queue.add(request)
                        successful = true
                    } catch (e: IllegalStateException) {
                        delay(1)
                    }
                }

            }.invokeOnCompletion {
                producerLatch.countDown()
            }
        }

        GlobalScope.launch(Dispatchers.Default.limitedParallelism(1)) {
            repeat(number) {
                var overflow = false
                val currentRequest = queue.poll(1000, TimeUnit.SECONDS)
                while(taskCount.get() >= concurrentCount) {
                    overflow = true
                    delay(1)
                }
                if (overflow) overflowCount.increment()

                taskCount.incrementAndGet()
                val startTime = System.currentTimeMillis()

                client.newCall(currentRequest).enqueue(object : Callback {
                    override fun onFailure(call: Call, e: IOException) {
                        applyForBothSuccessAndError(504)
                    }

                    override fun onResponse(call: Call, resp: Response) {
                        resp.body
                        applyForBothSuccessAndError(resp.code)
                    }

                    fun applyForBothSuccessAndError(status: Int) {
                        taskCount.decrementAndGet()
                        val endTime = System.currentTimeMillis()
                        list.add(Pair(status, endTime - startTime))

                        consumerLatch.countDown()
                        val percentage = BigDecimal.valueOf(consumerLatch.count / number.toDouble() * 100).setScale(2, RoundingMode.HALF_UP).toPlainString()
                        val totalElapsed = System.currentTimeMillis() - totalStartTime
                        val etc = totalElapsed / (100 - percentage.toDouble())  / 1000 * percentage.toDouble()
                        print("\r$percentage% tasks left, estimated time to complete is ${etc.roundToInt()} seconds..")
                    }
                })
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
