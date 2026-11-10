package ai.rask.sdk.errors;

import ai.rask.sdk.model.Problem;
import java.net.http.HttpHeaders;

/** 422: the request is well formed but its values are not accepted; {@link #errors()} lists them. */
public final class ValidationError extends RaskError {
    private static final long serialVersionUID = 1L;

    ValidationError(String message, int status, Problem problem, HttpHeaders headers, String idempotencyKey) {
        super(message, status, problem, headers, idempotencyKey, null);
    }
}
