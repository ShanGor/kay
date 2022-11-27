import cn.gzten.util.CliHistogram;
import cn.gzten.util.Pair;
import org.junit.jupiter.api.Test;

import java.util.LinkedList;
import java.util.Map;

public class TestCliHistogram {

    @Test
    public void test() {
        var list = new LinkedList<Map.Entry<Long, Integer>>();
        list.add(new Pair<>(1L, 750));
        list.add(new Pair<>(10L, 117));
        list.add(new Pair<>(20L, 241));
        list.add(new Pair<>(30L, 5291));
        list.add(new Pair<>(40L, 3206));
        list.add(new Pair<>(50L, 294));
        list.add(new Pair<>(60L, 1));
        list.add(new Pair<>(160L, 2));
        list.add(new Pair<>(170L, 7));
        list.add(new Pair<>(180L, 13));
        list.add(new Pair<>(190L, 18));
        list.add(new Pair<>(200L, 23));
        list.add(new Pair<>(210L, 21));
        list.add(new Pair<>(220L, 13));
        list.add(new Pair<>(230L, 2));
        list.add(new Pair<>(720L, 1));

        CliHistogram.printHistogram(list);
    }
}
