package ai.rask.sdk.errors;

import ai.rask.sdk.model.Problem;
import java.net.http.HttpHeaders;

/** 409: the resource's state does not allow this now. Not retried, except {@code idempotency_key_in_use}. */
public final class ConflictError extends RaskError {
    private static final long serialVersionUID = 1L;

    ConflictError(String message, int status, Problem problem, HttpHeaders headers, String idempotencyKey) {
        super(message, status, problem, headers, idempotencyKey, null);
    }
}
