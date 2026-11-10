package ai.rask.sdk.errors;

import ai.rask.sdk.model.Problem;
import java.net.http.HttpHeaders;

/** 415: the media type is not supported. */
public final class UnsupportedMediaTypeError extends RaskError {
    private static final long serialVersionUID = 1L;

    UnsupportedMediaTypeError(String message, int status, Problem problem, HttpHeaders headers, String idempotencyKey) {
        super(message, status, problem, headers, idempotencyKey, null);
    }
}
