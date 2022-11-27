package cn.gzten.kay;

import cn.gzten.util.Pair;
import cn.gzten.util.ParsedUrl;
import io.netty.channel.ChannelOption;
import io.netty.handler.ssl.SslContextBuilder;
import io.netty.handler.ssl.util.InsecureTrustManagerFactory;
import io.netty.handler.timeout.ReadTimeoutHandler;
import io.netty.handler.timeout.WriteTimeoutHandler;
import org.springframework.http.client.reactive.ReactorClientHttpConnector;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;
import reactor.netty.resources.ConnectionProvider;

import javax.net.ssl.SSLException;
import java.text.MessageFormat;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.LongAdder;
import java.util.regex.Pattern;

import static java.lang.System.out;

public class KayWithWebClient {
    private static final Pattern VARIABLE_INT = Pattern.compile(".*(?<placeholder>\\{int\\((?<from>\\d+)\\s*,\\s*(?<to>\\d+)\\)\\}).*");
    private static final Pattern VARIABLE_UUID = Pattern.compile(".*(?<placeholder>\\{uuid\\(\\s*\\)\\}).*");
    private final int totalRequestNumber;

    private final ParsedUrl parsedUrl;

    private final LinkedBlockingQueue<String> producerQueue = new LinkedBlockingQueue<>();
    private final WebClient httpClient;

    private final ConcurrentLinkedQueue<Pair<Integer, Long>> list;
    private final LinkedBlockingQueue<Integer> throttlingQueue;
    private final ConcurrentHashMap<String, Integer> errorReport;
    private final ConcurrentLinkedQueue<String> requestErrors;

    private final long overallStartTime;

    private final LongAdder taskOverflowCount;

    /**
     * To control the
     */
    private final CountDownLatch consumerLatch;

    public KayWithWebClient(String url, int concurrentCount, int number, long timeout) {
        this.totalRequestNumber = number;

        out.printf("Total number of requests: %d\n", number);
        out.printf("Concurrent: %d%n", concurrentCount);
        out.printf("URL: %s%n", url);
        out.printf("Http Timeout: %d seconds%n", timeout);
        System.setProperty("logging.level.root", "OFF");

        var parsedUrlOpt = ParsedUrl.from(url);
        if (parsedUrlOpt.isEmpty()) {
            System.err.printf("Cannot parse url: %s%n", url);
            System.exit(-1);
        }

        parsedUrl = parsedUrlOpt.get();

        out.printf("Base URL: %s%n", parsedUrl.baseUrl());
        out.printf("Endpoint: %s%n", parsedUrl.endpoint());

        httpClient = prepareWebClient(parsedUrl.baseUrl(), timeout, concurrentCount);
        list = new ConcurrentLinkedQueue<>();
        throttlingQueue = new LinkedBlockingQueue<>(concurrentCount);
        errorReport = new ConcurrentHashMap<>();
        requestErrors = new ConcurrentLinkedQueue<>();

        overallStartTime = System.currentTimeMillis();

        taskOverflowCount = new LongAdder();

        consumerLatch = new CountDownLatch(number);
    }

    public int run(int parallelismToIssueAsyncRequests) throws InterruptedException {
        // Produce endpoints, endpoints could be variable
        Thread.ofVirtual().name("produceUrlsThread").start(() -> produceEndpoints(producerQueue, parsedUrl.endpoint(), totalRequestNumber));

        // Issue requests. Now it is using platform threads to do it, because virtual one here will get stuck in Native Image
        try(var executor = Executors.newFixedThreadPool(parallelismToIssueAsyncRequests, Thread.ofPlatform().factory())) {
            for (int i = 0; i< totalRequestNumber; i++) {
                executor.submit(this::issueAnHttpRequest);
            }
        } catch (RuntimeException e) {
            e.printStackTrace();
            return -1;
        }

        // Wait for requests to be completed and then produce report
        consumerLatch.await(10, TimeUnit.SECONDS);
        out.println("\nConsumers done the job!");
        produceReport();
        return 0;
    }

    public void issueAnHttpRequest() {
        try {
            var waitQueueStartTime = System.currentTimeMillis();
            throttlingQueue.put(1);

            var waitQueueEndTime = System.currentTimeMillis();
            taskOverflowCount.add(waitQueueEndTime - waitQueueStartTime);

            var currentEndpoint = producerQueue.take();

            var startTime = System.currentTimeMillis();

            httpClient.get().uri(currentEndpoint).exchangeToMono (resp -> {
                var status = resp.statusCode().value();

                onCompleteOrException(status, startTime, list, requestErrors, null);
                return Mono.empty();
            }).onErrorComplete (e-> {
                int status;
                if (e.getMessage() != null && e.getMessage().startsWith("Connection refused")){
                    status = 503;
                } else{
                    status = 504;
                }

                onCompleteOrException(status, startTime, list, requestErrors, e);
                return true;
            }).doFinally (signalType -> {
                try {
                    throttlingQueue.take();
                    var percentage = roundTo2Digits(consumerLatch.getCount() * 100.0 / totalRequestNumber);
                    var totalElapsed = System.currentTimeMillis() - overallStartTime;
                    var etc = totalElapsed / (100.0 - percentage)  / 1000 * percentage;
                    out.print(MessageFormat.format("\r{0}% tasks left, estimated time to complete is {1} seconds..", percentage, (int)etc));
                } catch (InterruptedException e) {
                    throw new RuntimeException(e);
                } finally {
                    consumerLatch.countDown();
                }
            }).subscribe();
        } catch (InterruptedException e) {
            throw new RuntimeException(e);
        }
    }

    public final void produceReport() {
        var totalEndTime = System.currentTimeMillis();

        var countTable = new HashMap<Integer, Integer>();
        var maxTime = new AtomicLong(0L);
        var minTime = new AtomicLong(100000L);
        list.forEach( element -> {
            var status = element.first();
            var milliseconds = element.second();
            accumulateCounterMap(countTable, status);

            if (status == 200 && maxTime.get() < milliseconds) {
                maxTime.set(milliseconds);
            }

            if (status == 200 && minTime.get() > milliseconds) {
                minTime.set(milliseconds);
            }
        });

        var totalSeconds = (totalEndTime - overallStartTime) / 1000.0;
        out.printf("\nTotal time: %.3f seconds%n", totalSeconds);
        out.printf("QPS: %.2f queries per second%n", roundTo2Digits(totalRequestNumber / totalSeconds));
        out.println(countTable);
        out.printf("Max time: %.3f seconds%n", maxTime.get()/1000.0);
        out.printf("Min time: %.3f seconds%n", minTime.get()/1000.0);

        out.printf("Full concurrency CPU waiting time total: %.3f seconds. (Could be more than total time because there are more than 1 CPUs)%n", taskOverflowCount.sum() / 1000.0);

        if (requestErrors.isEmpty()) {
            out.println("Congratulations! No error found!");
        } else {
            requestErrors.forEach(msg -> accumulateCounterMap(errorReport, msg));

            out.println("Errors statistics: [Counts]: [Error message] ");
            errorReport.entrySet().stream()
                    .sorted((a, b) -> b.getValue().compareTo(a.getValue()))
                    .forEach(entry -> out.printf("\t[%d]: %s%n", entry.getValue(), entry.getKey()));
        }
    }

    /**
     * Produce endpoints for the consumer to start http requests.
     */
    public static final void produceEndpoints(final LinkedBlockingQueue<String> producerQueue,
                                              final String endpoint,
                                              final int number) {
        try {
            var matcherOfVarInt = VARIABLE_INT.matcher(endpoint);
            if (matcherOfVarInt.matches()) {
                var from = Integer.parseInt(matcherOfVarInt.group("from"));
                var to = Integer.parseInt(matcherOfVarInt.group("to"));
                var placeholder = matcherOfVarInt.group("placeholder");
                out.printf("Random int id is from `%d` to `%d`%n", from, to);

                for (int i=0; i < number; i++) {
                    var newEndpoint = endpoint.replace(placeholder, randomInt(from, to).toString());
                    producerQueue.put(newEndpoint);
                }
            } else {
                var matcherOfVarUuid = VARIABLE_UUID.matcher(endpoint);
                if (matcherOfVarUuid.matches()) {
                    var placeholder = matcherOfVarUuid.group("placeholder");
                    out.println("Expecting random uuid()");
                    for (int i=0; i < number; i++) {
                        var newEndpoint = endpoint.replace(placeholder, UUID.randomUUID().toString());
                        producerQueue.put(newEndpoint);
                    }
                } else {
                    for (int i=0; i < number; i++) {
                        producerQueue.put(endpoint);
                    }
                }
            }
        } catch (InterruptedException e) {
            throw new RuntimeException(e);
        }
    }

    public static final double roundTo2Digits(double d) {
        return Math.round(d * 100) / 100.0;
    }

    public static final void onCompleteOrException(
            int status,
            long startTime,
            ConcurrentLinkedQueue<Pair<Integer, Long>> list,
            ConcurrentLinkedQueue<String> errors,
            Throwable exception ) {
        var endTime = System.currentTimeMillis();
        list.add(new Pair<>(status, endTime - startTime));
        if (exception != null) {
            errors.add(exception.getMessage());
        }
    }

    public static final WebClient prepareWebClient(String baseUrl, long timeout, int concurrentCount) {
        var timeoutMilliseconds = timeout * 1000L;
        var connectionProvider = ConnectionProvider.builder("myConnectionPool")
                .maxConnections(concurrentCount)
                .pendingAcquireMaxCount(concurrentCount).build();
        var httpClient = reactor.netty.http.client.HttpClient.create(connectionProvider)
                .option(ChannelOption.CONNECT_TIMEOUT_MILLIS, 10_000)
                .responseTimeout(Duration.ofMillis(timeoutMilliseconds))
                .doOnConnected ( conn ->
                conn.addHandlerLast(new ReadTimeoutHandler(timeoutMilliseconds, TimeUnit.MILLISECONDS))
                        .addHandlerLast(new WriteTimeoutHandler(timeoutMilliseconds, TimeUnit.MILLISECONDS))
        );
        if (baseUrl.startsWith("https:")) {
            httpClient = httpClient.secure( t ->
                    {
                        try {
                            t.sslContext(SslContextBuilder.forClient()
                                    .trustManager(InsecureTrustManagerFactory.INSTANCE)
                                    .build());
                        } catch (SSLException e) {
                            throw new RuntimeException(e);
                        }
                    }
            );
        }

        return WebClient.builder()
                .clientConnector(new ReactorClientHttpConnector(httpClient))
                .baseUrl(baseUrl)
                .build();
    }

    public static Integer randomInt(int from, int to) {
        return new Random().nextInt(from, to);
    }

    /**
     * Not thread safe.
     */
    public static final <K> void accumulateCounterMap(Map<K, Integer> map, K key) {
        if (map.containsKey(key)) {
            map.put(key, map.get(key) + 1);
        } else {
            map.put(key, 1);
        }
    }

}


