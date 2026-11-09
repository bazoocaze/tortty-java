import checks.Suites;

import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Runs the same headless checks the jar runs with {@code --selftest} (`B24`):
 * one source of truth, so a check cannot pass here and fail there.
 */
class SelftestTest {

    @Test
    void allChecksPass() {
        ByteArrayOutputStream buf = new ByteArrayOutputStream();
        int failed = Suites.run(new PrintStream(buf, true, StandardCharsets.UTF_8));
        assertEquals(0, failed, buf.toString(StandardCharsets.UTF_8));
    }
}
