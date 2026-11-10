package ai.rask.sdk.errors;

import ai.rask.sdk.model.Problem;
import java.net.http.HttpHeaders;

/** 401: the API key is missing, invalid or revoked. */
public final class AuthenticationError extends RaskError {
    private static final long serialVersionUID = 1L;

    AuthenticationError(String message, int status, Problem problem, HttpHeaders headers, String idempotencyKey) {
        super(message, status, problem, headers, idempotencyKey, null);
    }
}
