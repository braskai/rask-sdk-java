package ai.rask.sdk.errors;

import ai.rask.sdk.model.Problem;
import java.net.http.HttpHeaders;

/** 428: the operation needs {@code If-Match}. Never retried automatically. */
public final class PreconditionRequiredError extends RaskError {
    private static final long serialVersionUID = 1L;

    PreconditionRequiredError(String message, int status, Problem problem, HttpHeaders headers, String idempotencyKey) {
        super(message, status, problem, headers, idempotencyKey, null);
    }
}
