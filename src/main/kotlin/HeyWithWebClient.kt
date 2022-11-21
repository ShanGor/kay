import com.xenomachina.argparser.ArgParser
import io.netty.channel.ChannelOption
import io.netty.handler.timeout.ReadTimeoutHandler
import io.netty.handler.timeout.WriteTimeoutHandler
import kotlinx.coroutines.*
import org.springframework.http.client.reactive.ReactorClientHttpConnector
import org.springframework.web.reactive.function.client.WebClient
import reactor.core.publisher.Mono
import reactor.netty.resources.ConnectionProvider
import java.math.BigDecimal
import java.math.RoundingMode
import java.time.Duration
import java.util.concurrent.*
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.LongAdder
import java.util.regex.Pattern
import kotlin.math.roundToInt
import kotlin.random.Random


@OptIn(ExperimentalCoroutinesApi::class)
fun heyWithWebClient(args: Array<String>) {
    val httpPattern = Pattern.compile("^(?<baseUrl>http://[^/]+)(?<endpoint>.*)$")
    val variableInt = Pattern.compile(".*(?<placeholder>\\{int\\((?<from>\\d+)\\s*,\\s*(?<to>\\d+)\\)\\}).*")
    ArgParser(args).parseInto(::HeyArgs).run {
        println("Total number of requests: $number")
        println("Concurrent: $concurrentCount")
        println("URL: $url")
        println("Http Timeout: $timeout seconds")
        System.setProperty("logging.level.root", "OFF")
        var m = httpPattern.matcher(url)
        var baseUrl: String?
        var endpoint: String?
        if (m.matches()) {
            baseUrl = m.group("baseUrl")
            endpoint = m.group("endpoint")
        } else {
            error("Cannot parse url: $url")
            return
        }

        println("Base URL: $baseUrl")
        println("Endpoint: $endpoint")
        val timeoutMilliseconds = timeout.toInt() * 1000L
        val connectionProvider = ConnectionProvider.builder("myConnectionPool")
            .maxConnections(concurrentCount)
            .pendingAcquireMaxCount(concurrentCount).build();
        val httpClient = reactor.netty.http.client.HttpClient.create(connectionProvider)
            .option(ChannelOption.CONNECT_TIMEOUT_MILLIS, 5000)
            .responseTimeout(Duration.ofMillis(timeoutMilliseconds))
            .doOnConnected { conn ->
                conn.addHandlerLast(ReadTimeoutHandler(timeoutMilliseconds, TimeUnit.MILLISECONDS))
                    .addHandlerLast(WriteTimeoutHandler(timeoutMilliseconds, TimeUnit.MILLISECONDS))
            }
        val client = WebClient.builder()
            .clientConnector(ReactorClientHttpConnector(httpClient))
            .baseUrl(baseUrl)
            .build()
        val producerQueue = LinkedBlockingQueue<String>()

        m = variableInt.matcher(endpoint)
        if (m.matches()) {
            val from = m.group("from").toInt()
            val to = m.group("to").toInt()
            val placeholder = m.group("placeholder")
            println("Random int id is from `$from` to `$to`")
            repeat(number) {
                GlobalScope.launch {
                    val newEndpoint = endpoint.replace(placeholder, "${Random.nextInt(from, to)}")
                    producerQueue.add(newEndpoint)
                }
            }
        } else {
            repeat(number) {
                GlobalScope.launch {
                    producerQueue.add(endpoint)
                }
            }
        }

        val list = ConcurrentLinkedQueue<Pair<Int, Long>>()
        val limitingQueue = LinkedBlockingQueue<Int>(concurrentCount)
        val errorMap = ConcurrentHashMap<String, Int>()
        val errors = ConcurrentLinkedQueue<String>()

        val totalStartTime = System.currentTimeMillis()

        val overflowCount = LongAdder()

        val consumerLatch = CountDownLatch(number)

        runBlocking(Dispatchers.IO.limitedParallelism(2)) {
            repeat(number) {
                launch {
                    var overflow = false
                    var enqueueSuccessful = false
                    while (!enqueueSuccessful) {
                        enqueueSuccessful = try {
                            limitingQueue.add(1)
                            true
                        } catch (e: IllegalStateException) {
                            delay(1)
                            overflow = true
                            false
                        }
                    }
                    if (overflow) overflowCount.increment()
                    val currentEndpoint = producerQueue.poll()

                    val startTime = System.currentTimeMillis()

                    client.get().uri(currentEndpoint).exchangeToMono { resp ->
                        val status =
                            if (resp.statusCode().is2xxSuccessful) {
                                resp.statusCode().value()
                            } else {
                                resp?.statusCode()?.value()?:504
                            }

                        val endTime = System.currentTimeMillis()
                        list.add(Pair(status, endTime - startTime))
                        Mono.empty<String>()
                    }.doOnError{ e ->
                        val endTime = System.currentTimeMillis()
                        list.add(Pair(504, endTime - startTime))
                        errors.add(e.message)
                    }.doFinally {
                        limitingQueue.poll()
                        consumerLatch.countDown()
                        val percentage = BigDecimal.valueOf(consumerLatch.count / number.toDouble() * 100).setScale(2, RoundingMode.HALF_UP).toPlainString()
                        val totalElapsed = System.currentTimeMillis() - totalStartTime
                        val etc = totalElapsed / (100 - percentage.toDouble())  / 1000 * percentage.toDouble()
                        print("\r$percentage% tasks left, estimated time to complete is ${etc.roundToInt()} seconds..")
                    }.subscribe()
                }
            }
        }

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


        println("\nTotal time: ${(totalEndTime - totalStartTime) / 1000.0} seconds")
        println(countTable)
        println("Max time: ${maxTime.get()/1000.0} seconds")
        println("Min time: ${minTime.get()/1000.0} seconds")

        println("Full concurrency time count: ${overflowCount.sum()}")

        if (errors.isEmpty()) {
            println("Congratulations! No error found!")
        } else {
            errors.forEach{ msg ->
                if (errorMap.containsKey(msg)) {
                    errorMap[msg] = errorMap[msg]!! + 1
                } else {
                    errorMap[msg] = 1
                }
            }

            println("Errors statistics: [Counts]: [Error message] ")
            errorMap.toList()
                .sortedWith { a, b -> b.second.compareTo(a.second) }
                .forEach { (error, count) ->
                    println("\t[$count]:$error")
                }
        }
    }

}
