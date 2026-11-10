package ai.rask.sdk.errors;

import ai.rask.sdk.model.Problem;
import java.net.http.HttpHeaders;

/** 500: the server failed. Not retried, because the failure may recur. */
public final class InternalServerError extends RaskError {
    private static final long serialVersionUID = 1L;

    InternalServerError(String message, int status, Problem problem, HttpHeaders headers, String idempotencyKey) {
        super(message, status, problem, headers, idempotencyKey, null);
    }
}
