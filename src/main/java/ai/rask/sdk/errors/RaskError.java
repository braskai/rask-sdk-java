package ai.rask.sdk.errors;

import ai.rask.sdk.internal.Headers;
import ai.rask.sdk.model.CreditType;
import ai.rask.sdk.model.Problem;
import ai.rask.sdk.model.ProblemFieldError;
import ai.rask.sdk.model.ProblemRecommendation;
import ai.rask.sdk.model.ProblemRetry;
import java.net.http.HttpHeaders;
import java.time.Duration;
import java.util.Collections;
import java.util.List;
import java.util.Optional;

/**
 * Base of everything the SDK raises. The name follows the design shared by all Rask SDKs; despite the suffix this is an
 * ordinary unchecked exception, not a {@link java.lang.Error}.
 *
 * <p>A problem from the API has every Problem member through {@link #problem()}, including members and codes this SDK
 * version does not know. {@link #status()} is 0 when no HTTP answer was received.
 */
public class RaskError extends RuntimeException {
    private static final long serialVersionUID = 1L;

    private final int status;
    private final transient Problem problem;
    private final transient HttpHeaders headers;
    private final String idempotencyKey;

    /**
     * Creates an error.
     *
     * @param message the message, free of secrets
     * @param status the HTTP status, or 0
     * @param problem the problem body, or null when the body was not one
     * @param headers the response headers, or null
     * @param idempotencyKey the key that was sent, or null
     * @param cause the cause, or null
     */
    public RaskError(String message, int status, Problem problem, HttpHeaders headers, String idempotencyKey,
            Throwable cause) {
        super(message, cause);
        this.status = status;
        this.problem = problem;
        this.headers = headers;
        this.idempotencyKey = idempotencyKey;
    }

    /**
     * Picks the class for an HTTP status.
     *
     * @param status the HTTP status
     * @param problem the problem body, or null
     * @param headers the response headers
     * @param idempotencyKey the key that was sent, or null
     * @return the error to raise
     */
    public static RaskError fromResponse(int status, Problem problem, HttpHeaders headers, String idempotencyKey) {
        String message = message(status, problem, headers);
        switch (status) {
            case 400:
                return new BadRequestError(message, status, problem, headers, idempotencyKey);
            case 401:
                return new AuthenticationError(message, status, problem, headers, idempotencyKey);
            case 402:
                return new InsufficientBalanceError(message, status, problem, headers, idempotencyKey);
            case 403:
                return new PermissionDeniedError(message, status, problem, headers, idempotencyKey);
            case 404:
                return new NotFoundError(message, status, problem, headers, idempotencyKey);
            case 409:
                return new ConflictError(message, status, problem, headers, idempotencyKey);
            case 410:
                return new GoneError(message, status, problem, headers, idempotencyKey);
            case 412:
                return new PreconditionFailedError(message, status, problem, headers, idempotencyKey);
            case 413:
                return new PayloadTooLargeError(message, status, problem, headers, idempotencyKey);
            case 415:
                return new UnsupportedMediaTypeError(message, status, problem, headers, idempotencyKey);
            case 422:
                return new ValidationError(message, status, problem, headers, idempotencyKey);
            case 428:
                return new PreconditionRequiredError(message, status, problem, headers, idempotencyKey);
            case 429:
                return new RateLimitError(message, status, problem, headers, idempotencyKey);
            case 500:
                return new InternalServerError(message, status, problem, headers, idempotencyKey);
            case 502:
            case 503:
            case 504:
                return new ServiceUnavailableError(message, status, problem, headers, idempotencyKey);
            default:
                return new RaskError(message, status, problem, headers, idempotencyKey, null);
        }
    }

    private static String message(int status, Problem problem, HttpHeaders headers) {
        StringBuilder out = new StringBuilder("HTTP ").append(status);
        if (problem != null && problem.code() != null) {
            out.append(' ').append(problem.code());
        }
        if (problem != null && problem.detail() != null) {
            out.append(": ").append(problem.detail());
        }
        headers.firstValue("X-Request-Id").ifPresent(id -> out.append(" (request_id ").append(id).append(')'));
        return out.toString();
    }

    /**
     * Returns the HTTP status.
     *
     * @return the status, or 0 when no answer was received
     */
    public final int status() {
        return status;
    }

    /**
     * Returns the problem body.
     *
     * @return the problem, or null when the body was not one (for example a gateway's HTML page)
     */
    public final Problem problem() {
        return problem;
    }

    /**
     * Reads one Problem member by its JSON name, known to this SDK version or not.
     *
     * @param name the JSON member name
     * @return the value, or null
     */
    public final Object member(String name) {
        return problem == null ? null : problem.get(name);
    }

    /**
     * Returns the stable problem code; branch on this value. Codes unknown to this SDK version arrive intact.
     *
     * @return the code, or null
     */
    public final String code() {
        return problem == null ? null : problem.code();
    }

    /**
     * Returns the documentation URL of the problem.
     *
     * @return the type, or null
     */
    public final String type() {
        return problem == null ? null : problem.type();
    }

    /**
     * Returns the short summary.
     *
     * @return the title, or null
     */
    public final String title() {
        return problem == null ? null : problem.title();
    }

    /**
     * Returns the explanation of this occurrence.
     *
     * @return the detail, or null
     */
    public final String detail() {
        return problem == null ? null : problem.detail();
    }

    /**
     * Returns {@code X-Request-Id}; quote it in support requests.
     *
     * @return the request id, or null
     */
    public final String requestId() {
        return headers == null ? null : headers.firstValue("X-Request-Id").orElse(null);
    }

    /**
     * Tells whether a retry can succeed; {@link #retry()} says how.
     *
     * @return the Problem's retryable member, or null
     */
    public final Boolean retryable() {
        return problem == null ? null : problem.retryable();
    }

    /**
     * Says how to retry.
     *
     * @return the Problem's retry member, or null
     */
    public final ProblemRetry retry() {
        return problem == null ? null : problem.retry();
    }

    /**
     * Says what has to happen before a retry can succeed.
     *
     * @return the Problem's recommendation member, or null
     */
    public final ProblemRecommendation recommendation() {
        return problem == null ? null : problem.recommendation();
    }

    /**
     * Names the next call or field to try.
     *
     * @return the Problem's hint member, or null
     */
    public final String hint() {
        return problem == null ? null : problem.hint();
    }

    /**
     * Lists the failing fields.
     *
     * @return the entries; empty when the problem is not about fields
     */
    public final List<ProblemFieldError> errors() {
        List<ProblemFieldError> errors = problem == null ? null : problem.errors();
        return errors == null ? Collections.emptyList() : errors;
    }

    /**
     * Names the credit pool concerned, for balance problems.
     *
     * @return the pool, or null
     */
    public final CreditType creditType() {
        return problem == null ? null : problem.creditType();
    }

    /**
     * Returns the minutes the request needs, for balance problems.
     *
     * @return the minutes, or null
     */
    public final Integer requiredMinutes() {
        return problem == null ? null : problem.requiredMinutes();
    }

    /**
     * Returns the minutes available, for balance problems.
     *
     * @return the minutes, or null
     */
    public final Integer balanceMinutes() {
        return problem == null ? null : problem.balanceMinutes();
    }

    /**
     * Returns the project credits the request needs, for balance problems.
     *
     * @return the credits, or null
     */
    public final Integer requiredProjects() {
        return problem == null ? null : problem.requiredProjects();
    }

    /**
     * Returns the project credits available, for balance problems.
     *
     * @return the credits, or null
     */
    public final Integer balanceProjects() {
        return problem == null ? null : problem.balanceProjects();
    }

    /**
     * Returns the response headers.
     *
     * @return the headers, or null when no answer was received
     */
    public final HttpHeaders headers() {
        return headers;
    }

    /**
     * Reads one response header.
     *
     * @param name the header name, in any case
     * @return its first value, or null
     */
    public final String header(String name) {
        return headers == null ? null : headers.firstValue(name).orElse(null);
    }

    /**
     * Returns {@code Retry-After}.
     *
     * @return the delay, if the server sent one
     */
    public final Optional<Duration> retryAfter() {
        return Optional.ofNullable(Headers.retryAfter(headers));
    }

    /**
     * Returns the {@code Idempotency-Key} that was sent. Reuse it to retry this logical call safely.
     *
     * @return the key, or null when the operation takes none
     */
    public final String idempotencyKey() {
        return idempotencyKey;
    }
}
