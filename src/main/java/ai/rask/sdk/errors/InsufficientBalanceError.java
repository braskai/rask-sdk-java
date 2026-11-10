package ai.rask.sdk.errors;

import ai.rask.sdk.model.Problem;
import java.net.http.HttpHeaders;

/**
 * 402: the payer lacks the credits; {@link #creditType()}, {@link #requiredMinutes()} and {@link #balanceMinutes()} say
 * by how much.
 */
public final class InsufficientBalanceError extends RaskError {
    private static final long serialVersionUID = 1L;

    InsufficientBalanceError(String message, int status, Problem problem, HttpHeaders headers, String idempotencyKey) {
        super(message, status, problem, headers, idempotencyKey, null);
    }
}
