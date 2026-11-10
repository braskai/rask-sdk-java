package ai.rask.sdk.errors;

import ai.rask.sdk.model.Problem;
import java.net.http.HttpHeaders;

/** 410: the resource has expired. */
public final class GoneError extends RaskError {
    private static final long serialVersionUID = 1L;

    GoneError(String message, int status, Problem problem, HttpHeaders headers, String idempotencyKey) {
        super(message, status, problem, headers, idempotencyKey, null);
    }
}
