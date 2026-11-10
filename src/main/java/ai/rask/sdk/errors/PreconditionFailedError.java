package ai.rask.sdk.errors;

import ai.rask.sdk.model.Problem;
import java.net.http.HttpHeaders;

/** 412: the resource changed since it was read. Never retried automatically: read it again and decide. */
public final class PreconditionFailedError extends RaskError {
    private static final long serialVersionUID = 1L;

    PreconditionFailedError(String message, int status, Problem problem, HttpHeaders headers, String idempotencyKey) {
        super(message, status, problem, headers, idempotencyKey, null);
    }

    /**
     * Returns the resource's current ETag.
     *
     * @return the ETag, or null
     */
    public String currentEtag() {
        return problem() == null ? null : problem().currentEtag();
    }
}
