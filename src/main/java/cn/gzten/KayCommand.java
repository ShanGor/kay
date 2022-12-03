package cn.gzten;


import cn.gzten.kay.KayWithWebClient;
import cn.gzten.util.CliHistogram;
import lombok.Data;
import picocli.CommandLine;

import java.util.List;
import java.util.Locale;
import java.util.concurrent.Callable;

@Data
@CommandLine.Command(name = "Kay", mixinStandardHelpOptions = true, version = "kay 1.0",
        description = "Peek test tool with parameters like ab / hey!")
public class KayCommand implements Callable<Integer> {

    @CommandLine.Parameters(index = "0", description = "The url to be tested.")
    private String url;

    @CommandLine.Option(names = {"-c", "--concurrency"}, description = "concurrent requests running at the same time, default as 5.")
    private int concurrency = 5;

    @CommandLine.Option(names = {"-n", "--number"}, description = "number of requests, default as 10")
    private int number = 10;

    @CommandLine.Option(names = {"-t", "--timeout"}, description = "http timeout in seconds, default as 30")
    private long timeout = 30L;

    @CommandLine.Option(names = {"-p", "--parallelismToIssueAsyncRequests"},
            description = "parallelism to issue async requests, default as 1. It is different from concurrency. Concurrency means active connections to the server, this param is the pace to send requests to the server, if it is high, could lead to `Connection refused` error!")
    private int parallelismToIssueAsyncRequests = 1;

    @CommandLine.Option(names = {"-h", "--histogram-max-blocks"}, description = "histogram-max-blocks, default as 100.")
    private int histogramMaxBlocks = 100;
    @CommandLine.Option(names = {"-b", "--histogram-block-char"}, description = "histogram block char, default as *.")
    private String histogramBlock = "*";

    @CommandLine.Option(names = {"-d", "--data"}, description = "post / put data as a string, or a file with URI like file:///c:/tmp/my.json")
    private String data = null;

    @CommandLine.Option(names = {"-m", "--method"}, description = "Http Method [GET|PUT|POST|DELETE|PATCH], default as GET")
    private String httpMethod = null;

    @CommandLine.Option(names = {"-H", "--header"}, description = "Http Header")
    private String[] httpHeaders = {};

    private static final List<String> VALID_METHODS = List.of("GET", "PUT", "POST", "DELETE", "PATCH");

    @Override
    public Integer call() throws Exception {
        CliHistogram.MAX_BLOCK = histogramMaxBlocks;
        CliHistogram.BLOCK = String.valueOf(histogramBlock);

        if (httpMethod==null) {
            if (data != null) {
                httpMethod = "POST";
            } else {
                httpMethod = "GET";
            }
        } else {
            if (!verifyHttpMethod()) {
                System.err.printf("Http method provided is not supported: %s\n", httpMethod);
                System.exit(1);
            }
        }
        if (data == null) {
            data = "";
        }

        return new KayWithWebClient(this).run(parallelismToIssueAsyncRequests);
    }

    public static void main(String[] args) {
        System.setProperty("file.encoding", "UTF-8");
        System.exit(new CommandLine(new KayCommand()).execute(args));
    }

    private boolean verifyHttpMethod() {
        for(String method : VALID_METHODS) {
            if (method.equals(httpMethod.trim().toUpperCase(Locale.ROOT))) {
                if (!method.equals(httpMethod)) {
                    httpMethod = method;
                }
                return true;
            }
        }
        return false;
    }
}
