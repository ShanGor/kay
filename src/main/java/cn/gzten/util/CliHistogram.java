package cn.gzten.util;

import lombok.AllArgsConstructor;
import lombok.Data;

import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

public class CliHistogram {
    public static int MAX_BLOCK = 50;
    /**
     * The ■ 0x25A0 does not support in Windows Native Image, changed to be *
     */
    public static String BLOCK = "*";

    public static void printHistogram(List<Map.Entry<Long, Integer>> list) {
        var maxPrefixLength = new AtomicInteger(0);
        var maxCount = new AtomicInteger(0);
        var lines = new LinkedList<LineData>();
        list.forEach(p -> {
            var timeCost = p.getKey();
            var count = p.getValue();
            if (maxCount.get() < count) {
                maxCount.set(count);
            }

            var s = "%.2f [%d]".formatted(timeCost / 1000.0, count);
            if (maxPrefixLength.get() < s.length()) {
                maxPrefixLength.set(s.length());
            }

            var line = new LineData(s, count);
            lines.add(line);
        });

        lines.forEach(lineData -> {
            var blanks = " ".repeat(1 + maxPrefixLength.get() - lineData.prefix.length());
            var percent = lineData.count / ((double)maxCount.get()) * MAX_BLOCK;

            System.out.printf("  %s%s|%s\n", lineData.prefix, blanks, BLOCK.repeat((int) percent));
        });

    }

    @Data
    @AllArgsConstructor
    private static class LineData {
        private String prefix;
        private int count;
    }

}
