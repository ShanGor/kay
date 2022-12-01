package cn.gzten.kay;

import org.junit.jupiter.api.Test;

import static java.lang.System.out;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class KayWithWebClientTests {
    @Test
    public void testPatternPathVarAtFile() {
        var o = KayWithWebClient.tryPathVariableFilePattern("http://localhost:8080/reports/{@ids.txt}");
        assertTrue (o.isPresent());
        var obj = o.get();
        out.println(obj.placeholder());
        out.println(obj.filePath());
        assertEquals("ids.txt", obj.filePath());
        assertEquals("{@ids.txt}", obj.placeholder());
    }

    @Test
    public void testTryPathVarPlaceholderPattern() {
        var opt = KayWithWebClient.tryPathVarPlaceholderPattern("http://localhost:8080/users/{int(1,1000000)}",
                PathVariableInt.PATTERN);
        assertTrue(opt.isPresent());
        out.println(opt.get());
        assertEquals("{int(1,1000000)}", opt.get());

        opt = KayWithWebClient.tryPathVarPlaceholderPattern("http://localhost:8080/users/{uuid( )}",
                KayWithWebClient.VARIABLE_UUID);
        assertTrue(opt.isPresent());
        out.println(opt.get());
        assertEquals("{uuid( )}", opt.get());
    }

    @Test
    public void testTryPathVariableIntPattern() {
        var opt = KayWithWebClient.tryPathVariableIntPattern("http://localhost:8080/users/{int(1,1000000)}");
        assertTrue(opt.isPresent());
        out.println(opt.get());
        assertEquals("{int(1,1000000)}", opt.get().placeholder());
        assertEquals(1, opt.get().from());
        assertEquals(1000000, opt.get().to());
    }
}
