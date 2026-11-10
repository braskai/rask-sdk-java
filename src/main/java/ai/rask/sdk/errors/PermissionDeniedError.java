package ai.rask.sdk.errors;

import ai.rask.sdk.model.Problem;
import java.net.http.HttpHeaders;

/** 403: the key may not do this; the Problem's {@code required_scopes} and {@code granted_scopes} say why. */
public final class PermissionDeniedError extends RaskError {
    private static final long serialVersionUID = 1L;

    PermissionDeniedError(String message, int status, Problem problem, HttpHeaders headers, String idempotencyKey) {
        super(message, status, problem, headers, idempotencyKey, null);
    }
}
