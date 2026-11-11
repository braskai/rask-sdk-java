package ai.rask.sdk.internal;

import java.net.http.HttpHeaders;
import java.time.Duration;
import java.time.Instant;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;

/** Reads headers with more than one form. Internal API. */
public final class Headers {
    /**
     * The longest delay {@code Retry-After} is read as: 2^31 seconds, the value HTTP caching takes for a number of
     * seconds too large to hold (RFC 9111, 1.2.2). It is about 68 years, so its milliseconds and nanoseconds fit a long
     * and a time or another wait can still be added to them.
     */
    private static final Duration MAX_RETRY_AFTER = Duration.ofSeconds(1L << 31);

    private Headers() {
    }

    /**
     * Reads {@code Retry-After} in either of its forms, seconds or an HTTP date.
     *
     * @param headers the response headers
     * @return the delay, never negative and never above 2^31 seconds, or null when absent or unreadable
     */
    public static Duration retryAfter(HttpHeaders headers) {
        String value = headers == null ? null : headers.firstValue("Retry-After").orElse(null);
        if (value == null) {
            return null;
        }
        String text = value.trim();
        Duration delay;
        try {
            delay = Duration.ofSeconds(Math.max(0, Long.parseLong(text)));
        } catch (NumberFormatException notSeconds) {
            if (text.matches("\\+?\\d+")) {
                // More seconds than a long holds: still a wait, and the longest there is.
                return MAX_RETRY_AFTER;
            }
            try {
                Instant at = ZonedDateTime.parse(text, DateTimeFormatter.RFC_1123_DATE_TIME).toInstant();
                delay = Duration.between(Instant.now(), at);
            } catch (DateTimeParseException notADate) {
                return null;
            }
        }
        if (delay.isNegative()) {
            return Duration.ZERO;
        }
        // Capped here, before anyone converts or adds: a date in the year 9999 does not fit a long of nanoseconds.
        return delay.compareTo(MAX_RETRY_AFTER) > 0 ? MAX_RETRY_AFTER : delay;
    }
}
