package ai.rask.sdk.errors;

import ai.rask.sdk.model.Problem;
import java.net.http.HttpHeaders;

/** 413: the request or the media is too large. */
public final class PayloadTooLargeError extends RaskError {
    private static final long serialVersionUID = 1L;

    PayloadTooLargeError(String message, int status, Problem problem, HttpHeaders headers, String idempotencyKey) {
        super(message, status, problem, headers, idempotencyKey, null);
    }
}
