package ai.rask.sdk.internal;

import java.net.http.HttpHeaders;
import java.time.Duration;
import java.time.Instant;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;

/** Reads headers with more than one form. Internal API. */
public final class Headers {
    private Headers() {
    }

    /**
     * Reads {@code Retry-After} in either of its forms, seconds or an HTTP date.
     *
     * @param headers the response headers
     * @return the delay, never negative, or null when absent or unreadable
     */
    public static Duration retryAfter(HttpHeaders headers) {
        String value = headers == null ? null : headers.firstValue("Retry-After").orElse(null);
        if (value == null) {
            return null;
        }
        String text = value.trim();
        try {
            return Duration.ofSeconds(Math.max(0, Long.parseLong(text)));
        } catch (NumberFormatException notSeconds) {
            try {
                Instant at = ZonedDateTime.parse(text, DateTimeFormatter.RFC_1123_DATE_TIME).toInstant();
                Duration delay = Duration.between(Instant.now(), at);
                return delay.isNegative() ? Duration.ZERO : delay;
            } catch (DateTimeParseException notADate) {
                return null;
            }
        }
    }
}
