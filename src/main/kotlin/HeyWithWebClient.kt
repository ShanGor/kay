import com.xenomachina.argparser.SystemExitException
import io.netty.channel.ChannelOption
import io.netty.handler.ssl.SslContextBuilder
import io.netty.handler.ssl.util.InsecureTrustManagerFactory
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
import java.util.UUID
import java.util.concurrent.*
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.LongAdder
import java.util.regex.Pattern
import kotlin.math.roundToInt
import kotlin.random.Random


@OptIn(ExperimentalCoroutinesApi::class)
fun heyWithWebClient(parsedArgs: HeyArgs) {
    val httpPattern = Pattern.compile("^(?<baseUrl>http[s]?://[^/]+)(?<endpoint>.*)$")
    val variableInt = Pattern.compile(".*(?<placeholder>\\{int\\((?<from>\\d+)\\s*,\\s*(?<to>\\d+)\\)\\}).*")
    val variableUuid = Pattern.compile(".*(?<placeholder>\\{uuid\\(\\s*\\)\\}).*")

    parsedArgs.run {
        if (url == null) {
            throw SystemExitException("Please try -h or --help to see the usage", 0)
        }

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

        val producerQueue = LinkedBlockingQueue<String>()

        m = variableInt.matcher(endpoint)
        GlobalScope.launch(Dispatchers.IO.limitedParallelism(1)) {
            if (m.matches()) {
                val from = m.group("from").toInt()
                val to = m.group("to").toInt()
                val placeholder = m.group("placeholder")
                println("Random int id is from `$from` to `$to`")
                repeat(number) {
                    val newEndpoint = endpoint.replace(placeholder, "${Random.nextInt(from, to)}")
                    producerQueue.put(newEndpoint)
                }
            } else {
                m = variableUuid.matcher(endpoint)
                if (m.matches()) {
                    val placeholder = m.group("placeholder")
                    println("Expecting random uuid()")
                    repeat(number) {
                        val newEndpoint = endpoint.replace(placeholder, UUID.randomUUID().toString())
                        producerQueue.put(newEndpoint)
                    }
                } else {
                    repeat(number) {
                        producerQueue.put(endpoint)
                    }
                }
            }
        }


        val client = prepareWebClient(baseUrl, timeout, concurrentCount)
        val list = ConcurrentLinkedQueue<Pair<Int, Long>>()
        val throttlingQueue = LinkedBlockingQueue<Int>(concurrentCount)
        val errorMap = ConcurrentHashMap<String, Int>()
        val errors = ConcurrentLinkedQueue<String>()

        val totalStartTime = System.currentTimeMillis()

        val overflowCount = LongAdder()

        val consumerLatch = CountDownLatch(number)

        runBlocking(Dispatchers.IO.limitedParallelism(2)) {
            repeat(number) {
                launch {
                    val waitQueueStartTime = System.currentTimeMillis()
                    throttlingQueue.put(1)
                    val waitQueueEndTime = System.currentTimeMillis()
                    overflowCount.add(waitQueueEndTime - waitQueueStartTime)

                    val currentEndpoint = producerQueue.take()

                    val startTime = System.currentTimeMillis()

                    client.get().uri(currentEndpoint).exchangeToMono { resp ->
                        val status = resp.statusCode().value()

                        onCompleteOrException(
                            status = status,
                            startTime = startTime,
                            errors = errors,
                            list = list,
                            exception = null)
                        Mono.empty<String>()
                    }.onErrorComplete {e->
                        val status = if (e.message != null && e.message!!.startsWith("Connection refused")) {
                            503
                        } else {
                            504
                        }

                        onCompleteOrException(
                            status = status,
                            startTime = startTime,
                            errors = errors,
                            list = list,
                            exception = e)
                        true
                    }.doFinally {
                        throttlingQueue.take()
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

        val totalSeconds = (totalEndTime - totalStartTime) / 1000.0
        println("\nTotal time: $totalSeconds seconds")
        println("QPS: ${roundTo2Digits(number / totalSeconds)} queries per second")
        println(countTable)
        println("Max time: ${maxTime.get()/1000.0} seconds")
        println("Min time: ${minTime.get()/1000.0} seconds")

        println("Full concurrency CPU waiting time total: ${overflowCount.sum() / 1000.0} seconds. (Could be more than total time because there are more than 1 CPUs)")

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

fun roundTo2Digits(d: Double): Double {
    return (d * 100).roundToInt() / 100.0
}

fun onCompleteOrException(
    status: Int,
    startTime: Long,
    list: ConcurrentLinkedQueue<Pair<Int, Long>>,
    errors: ConcurrentLinkedQueue<String>,
    exception: Throwable?) {
    val endTime = System.currentTimeMillis()
    list.add(Pair(status, endTime - startTime))
    if (exception != null) {
        errors.add(exception.message)
    }
}

fun prepareWebClient(baseUrl: String, timeout: Long, concurrentCount: Int): WebClient {
    val timeoutMilliseconds = timeout.toInt() * 1000L
    val connectionProvider = ConnectionProvider.builder("myConnectionPool")
        .maxConnections(concurrentCount)
        .pendingAcquireMaxCount(concurrentCount).build()
    var httpClient = reactor.netty.http.client.HttpClient.create(connectionProvider)
        .option(ChannelOption.CONNECT_TIMEOUT_MILLIS, 10_000)
        .responseTimeout(Duration.ofMillis(timeoutMilliseconds))
        .doOnConnected { conn ->
            conn.addHandlerLast(ReadTimeoutHandler(timeoutMilliseconds, TimeUnit.MILLISECONDS))
                .addHandlerLast(WriteTimeoutHandler(timeoutMilliseconds, TimeUnit.MILLISECONDS))
        }
    if (baseUrl.startsWith("https:")) {
        httpClient = httpClient.secure{ t ->
            t.sslContext(SslContextBuilder.forClient()
                .trustManager(InsecureTrustManagerFactory.INSTANCE)
                .build())
        }
    }

    return WebClient.builder()
        .clientConnector(ReactorClientHttpConnector(httpClient))
        .baseUrl(baseUrl)
        .build()
}