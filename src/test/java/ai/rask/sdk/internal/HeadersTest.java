package ai.rask.sdk.internal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.net.http.HttpHeaders;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** Retry-After values a server can send that no wait can be made of as they are. */
class HeadersTest {
    private static final Duration LONGEST = Duration.ofSeconds(1L << 31);

    private static Duration retryAfter(String value) {
        return Headers.retryAfter(HttpHeaders.of(Map.of("Retry-After", List.of(value)), (name, text) -> true));
    }

    /**
     * The code after the header turns the wait into milliseconds and nanoseconds and adds times to it. Beyond the cap
     * those overflow: an ArithmeticException out of the call, or a sum that wraps round into a sleep without end.
     */
    @Test
    void aRetryAfterBeyondTheCapIsCapped() {
        for (String value : List.of("2147483648", "2147483649", "9223372037", "9223372036854775807",
                "99999999999999999999", "Fri, 31 Dec 9999 23:59:59 GMT")) {
            assertEquals(LONGEST, retryAfter(value), value);
        }
        assertEquals(LONGEST.minusSeconds(1), retryAfter("2147483647"), "a number under the cap changed");
        assertEquals(Duration.ofSeconds(130), retryAfter("130"));
        for (String value : List.of("NaN", "Infinity", "-Infinity", "1e20")) {
            assertNull(retryAfter(value), value);
        }
    }
}
