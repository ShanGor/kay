package cn.gzten.kay;

import cn.gzten.util.CliHistogram;
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
import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.text.MessageFormat;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.LongAdder;
import java.util.regex.Pattern;

import static java.lang.System.out;

public class KayWithWebClient {
    public static final Pattern VARIABLE_UUID = Pattern.compile(".*(?<placeholder>\\{uuid\\(\\s*\\)}).*");

    private static final String  BLANK_20 = " ".repeat(20);
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
     * To control the process progress.
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
        consumerLatch.await();
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

                    out.print(MessageFormat.format("\r{0}% tasks left, estimated time to complete is {1} seconds..{2}", percentage, (int)etc, BLANK_20));
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
        var successSumCount = new LongAdder();
        var successSumTime = new LongAdder();
        list.forEach( element -> {
            var status = element.first();
            var milliseconds = element.second();
            accumulateCounterMap(countTable, status);

            if (status < 500) {
                successSumTime.add(milliseconds);
                successSumCount.increment();
                if (maxTime.get() < milliseconds) {
                    maxTime.set(milliseconds);
                }

                if (minTime.get() > milliseconds) {
                    minTime.set(milliseconds);
                }
            }

        });

        var totalSeconds = (totalEndTime - overallStartTime) / 1000.0;
        out.println();
        out.println("Summary:");
        out.printf("  Total: %.3f seconds%n", totalSeconds);
        out.printf("  Slowest: %.3f seconds%n", maxTime.get()/1000.0);
        out.printf("  Fastest: %.3f seconds%n", minTime.get()/1000.0);
        out.printf("  Average: %.3f seconds%n", successSumTime.doubleValue() / successSumCount.doubleValue() / 1000);
        out.printf("  Requests/sec: %.2f%n", roundTo2Digits(totalRequestNumber / totalSeconds));

        out.println();
        out.println("Response time histogram:");
        produceHistogram(list);
        out.println();
        out.println("Latency distribution:");
        statLatencyDistribution(list).forEach(p ->
                out.printf("  %s in %.3f seconds\n", p.first(), p.second() / 1000.0));

        out.println();
        out.println("Status code distribution:");
        countTable.entrySet().stream().sorted(Map.Entry.comparingByKey()).forEach(e -> {
            out.printf("  [%d] %d responses%n", e.getKey(), e.getValue());
        });

        out.println();
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

    public static final List<Pair<String, Long>> statLatencyDistribution(Collection<Pair<Integer, Long>> list) {
        var result = new LinkedList<Pair<String, Long>>();
        var latencies = list.stream().filter(p -> p.first() < 500)
                .sorted(Comparator.comparing(Pair::second))
                .map(p -> p.second())
                .toList();

        var total = latencies.size();
        switch (total) {
            case 1 -> result.add(new Pair<>("100%", latencies.get(0)));
            case 2 -> {
                result.add(new Pair<>("50%", latencies.get(0)));
                result.add(new Pair<>("100%", latencies.get(1)));
            }
            case 3 -> {
                result.add(new Pair<>("33%", latencies.get(0)));
                result.add(new Pair<>("66%", latencies.get(1)));
                result.add(new Pair<>("100%", latencies.get(2)));
            }
            case 4 -> {
                result.add(new Pair<>("25%", latencies.get(0)));
                result.add(new Pair<>("50%", latencies.get(1)));
                result.add(new Pair<>("75%", latencies.get(2)));
                result.add(new Pair<>("100%", latencies.get(3)));
            }
            case 5 -> {
                result.add(new Pair<>("20%", latencies.get(0)));
                result.add(new Pair<>("40%", latencies.get(1)));
                result.add(new Pair<>("60%", latencies.get(2)));
                result.add(new Pair<>("80%", latencies.get(3)));
                result.add(new Pair<>("100%", latencies.get(4)));
            }
            case 6 -> {
                result.add(new Pair<>("16.66%", latencies.get(0)));
                result.add(new Pair<>("33.33%", latencies.get(1)));
                result.add(new Pair<>("50%", latencies.get(2)));
                result.add(new Pair<>("66.66%", latencies.get(4)));
                result.add(new Pair<>("83.33%", latencies.get(4)));
                result.add(new Pair<>("100%", latencies.get(5)));
            }
            default -> {
                result.add(new Pair<>("10%", latencies.get((int) (total * 0.1))));
                result.add(new Pair<>("25%", latencies.get((int) (total * 0.25))));
                result.add(new Pair<>("50%", latencies.get((int) (total * 0.5))));
                result.add(new Pair<>("75%", latencies.get((int) (total * 0.75))));
                result.add(new Pair<>("90%", latencies.get((int) (total * 0.9))));
                result.add(new Pair<>("95%", latencies.get((int) (total * 0.95))));
                result.add(new Pair<>("99%", latencies.get((int) (total * 0.99))));
            }
        }
        return result;
    }

    public static final void produceHistogram(Collection<Pair<Integer, Long>> list) {
        var map = new HashMap<Long, Integer>();
        list.stream().filter(p -> p.first() < 500).forEach(p -> {
            var timeCost = p.second()/10 * 10;
            accumulateCounterMap(map, timeCost);
        });
        CliHistogram.printHistogram(map.entrySet().stream().sorted(Map.Entry.comparingByKey()).toList());
    }
    /**
     * Produce endpoints for the consumer to start http requests.
     */
    public static final void produceEndpoints(final LinkedBlockingQueue<String> producerQueue,
                                              final String endpoint,
                                              final int number) {
        try {
            var matchOfVarInt = tryPathVariableIntPattern(endpoint);
            if (matchOfVarInt.isPresent()) {
                var from = matchOfVarInt.get().from();
                var to = matchOfVarInt.get().to();
                var placeholder = matchOfVarInt.get().placeholder();
                out.printf("Random int id is from `%d` to `%d`%n", from, to);

                for (int i=0; i < number; i++) {
                    var newEndpoint = endpoint.replace(placeholder, randomInt(from, to).toString());
                    producerQueue.put(newEndpoint);
                }
                return;
            }

            var matcherOfVarUuid = tryPathVarPlaceholderPattern(endpoint, VARIABLE_UUID);
            if (matcherOfVarUuid.isPresent()) {
                var placeholder = matcherOfVarUuid.get();
                out.println("Expecting random uuid()");
                for (int i=0; i < number; i++) {
                    var newEndpoint = endpoint.replace(placeholder, UUID.randomUUID().toString());
                    producerQueue.put(newEndpoint);
                }
                return;
            }

            var opt = tryPathVariableFilePattern(endpoint);
            if (opt.isPresent()) {
                var placeholder = opt.get().placeholder();
                out.printf("Expecting random id from file %s\n", opt.get().filePath());
                try(var reader = new RandomAccessFile(opt.get().filePath(), "r")) {
                    var atLeastFoundOneLine = false;
                    for (int i=0; i < number; i++) {
                        var line =reader.readLine();
                        var foundLine = false;
                        while(!foundLine) {
                            if (line == null) {
                                if (!atLeastFoundOneLine) {
                                    throw new IOException("File cannot be empty!");
                                }
                                reader.seek(0);
                                line =reader.readLine();
                            }

                            if (line.trim().equals("")) {
                                line =reader.readLine();
                                foundLine = false;
                            } else {
                                atLeastFoundOneLine = true;
                                foundLine = true;
                            }
                        }

                        var newEndpoint = endpoint.replace(placeholder, line.trim());
                        producerQueue.put(newEndpoint);
                    }
                } catch (IOException e) {
                    System.err.println(e.getMessage());
                    e.printStackTrace();
                    System.exit(1);
                }

                return;
            }

            // When there is no special pattern for the endpoint
            for (int i=0; i < number; i++) {
                producerQueue.put(endpoint);
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


    public static final Optional<PathVariableFile> tryPathVariableFilePattern(final String url) {
        var m = PathVariableFile.PATTERN.matcher(url);
        if (m.matches()) {
            var placeholder = m.group("placeholder");
            var path = m.group("filePath");
            return Optional.of(new PathVariableFile(placeholder, path));
        } else {
            return Optional.empty();
        }
    }

    public static final Optional<String> tryPathVarPlaceholderPattern(final String url, final Pattern p) {
        var m = p.matcher(url);
        if (m.matches()) {
            return Optional.of(m.group("placeholder"));
        } else {
            return Optional.empty();
        }
    }

    public static final Optional<PathVariableInt> tryPathVariableIntPattern(final String url) {
        var m = PathVariableInt.PATTERN.matcher(url);
        if (m.matches()) {
            var from = Integer.parseInt(m.group("from"));
            var to = Integer.parseInt(m.group("to"));
            var placeholder = m.group("placeholder");
            return Optional.of(new PathVariableInt(placeholder, from, to));
        } else {
            return Optional.empty();
        }
    }
}


record PathVariableFile(String placeholder, String filePath) {
    static final Pattern PATTERN = Pattern.compile(".*(?<placeholder>\\{@(?<filePath>[^}]+)}).*");
}

record PathVariableInt(String placeholder, int from, int to) {
    static final Pattern PATTERN = Pattern.compile(".*(?<placeholder>\\{int\\((?<from>\\d+)\\s*,\\s*(?<to>\\d+)\\)\\}).*");
}