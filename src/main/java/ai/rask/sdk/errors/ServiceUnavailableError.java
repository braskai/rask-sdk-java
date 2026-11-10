package ai.rask.sdk.errors;

import ai.rask.sdk.model.Problem;
import java.net.http.HttpHeaders;

/** 502, 503 or 504 after the retries ran out. */
public final class ServiceUnavailableError extends RaskError {
    private static final long serialVersionUID = 1L;

    ServiceUnavailableError(String message, int status, Problem problem, HttpHeaders headers, String idempotencyKey) {
        super(message, status, problem, headers, idempotencyKey, null);
    }
}
