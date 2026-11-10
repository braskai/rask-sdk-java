package ai.rask.sdk.errors;

import ai.rask.sdk.model.Problem;
import java.net.http.HttpHeaders;

/** 429 after the retries ran out, or with a {@link #retryAfter()} longer than the retry budget. */
public final class RateLimitError extends RaskError {
    private static final long serialVersionUID = 1L;

    RateLimitError(String message, int status, Problem problem, HttpHeaders headers, String idempotencyKey) {
        super(message, status, problem, headers, idempotencyKey, null);
    }
}
