package ai.rask.sdk.errors;

import ai.rask.sdk.model.Problem;
import java.net.http.HttpHeaders;

/** 404: the resource does not exist in this workspace. */
public final class NotFoundError extends RaskError {
    private static final long serialVersionUID = 1L;

    NotFoundError(String message, int status, Problem problem, HttpHeaders headers, String idempotencyKey) {
        super(message, status, problem, headers, idempotencyKey, null);
    }
}
