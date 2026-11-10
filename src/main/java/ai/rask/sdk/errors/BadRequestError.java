package ai.rask.sdk.errors;

import ai.rask.sdk.model.Problem;
import java.net.http.HttpHeaders;

/** 400: the request itself is wrong, for example a stale cursor or a key in the query string. */
public final class BadRequestError extends RaskError {
    private static final long serialVersionUID = 1L;

    BadRequestError(String message, int status, Problem problem, HttpHeaders headers, String idempotencyKey) {
        super(message, status, problem, headers, idempotencyKey, null);
    }
}
