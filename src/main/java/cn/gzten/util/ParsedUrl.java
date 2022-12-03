package cn.gzten.util;

import java.util.Optional;
import java.util.regex.Pattern;

public record ParsedUrl(String baseUrl, String endpoint) {
    private static final Pattern HTTP_PATTERN = Pattern.compile("^(?<baseUrl>http[s]?://[^/]+)(?<endpoint>.*)$");
    public static Optional<ParsedUrl> from(String url) {
        var m = HTTP_PATTERN.matcher(url);
        if (m.matches()) {
            return Optional.of(new ParsedUrl(m.group("baseUrl"), m.group("endpoint")));
        } else {
            return Optional.empty();
        }
    }
}