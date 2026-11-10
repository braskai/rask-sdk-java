package ai.rask.sdk;

import ai.rask.sdk.internal.Headers;
import java.net.http.HttpHeaders;
import java.time.Duration;
import java.util.Optional;

/**
 * One operation's result: the decoded body plus the raw status and headers, with no second call.
 *
 * @param <T> the body type; {@link Void} for operations that answer 204
 */
public final class RaskResponse<T> {
    private final int status;
    private final HttpHeaders headers;
    private final T data;
    private final String idempotencyKey;

    /**
     * Creates a result. Applications receive these and have no need to create them.
     *
     * @param status the HTTP status
     * @param headers the response headers
     * @param data the decoded body, or null
     * @param idempotencyKey the key that was sent, or null
     */
    public RaskResponse(int status, HttpHeaders headers, T data, String idempotencyKey) {
        this.status = status;
        this.headers = headers;
        this.data = data;
        this.idempotencyKey = idempotencyKey;
    }

    /**
     * Returns the decoded body.
     *
     * @return the body; null on 204 and on 304
     */
    public T data() {
        return data;
    }

    /**
     * Returns the HTTP status.
     *
     * @return the status of the answer that ended the call
     */
    public int status() {
        return status;
    }

    /**
     * Returns all response headers.
     *
     * @return the headers; lookups ignore case
     */
    public HttpHeaders headers() {
        return headers;
    }

    /**
     * Reads one response header.
     *
     * @param name the header name, in any case
     * @return its first value, or null
     */
    public String header(String name) {
        return headers.firstValue(name).orElse(null);
    }

    /**
     * Returns the {@code Idempotency-Key} that was sent, the same on every retry of this call.
     *
     * @return the key, or null when the operation takes none
     */
    public String idempotencyKey() {
        return idempotencyKey;
    }

    /**
     * Returns {@code ETag}, quotes included, ready to pass as {@code ifMatch}.
     *
     * @return the ETag, or null
     */
    public String etag() {
        return header("ETag");
    }

    /**
     * Returns {@code Location}. It says {@code /v3/...} whatever the base URL: use the id in the body, not this path,
     * to make the next call.
     *
     * @return the location, or null
     */
    public String location() {
        return header("Location");
    }

    /**
     * Returns {@code X-Request-Id}; quote it in support requests.
     *
     * @return the request id, or null
     */
    public String requestId() {
        return header("X-Request-Id");
    }

    /**
     * Tells whether the server replayed a stored response for this idempotency key.
     *
     * @return true when {@code Idempotent-Replayed: true} came back
     */
    public boolean idempotentReplayed() {
        return "true".equalsIgnoreCase(header("Idempotent-Replayed"));
    }

    /**
     * Returns {@code Retry-After}.
     *
     * @return the delay, if the server sent one
     */
    public Optional<Duration> retryAfter() {
        return Optional.ofNullable(Headers.retryAfter(headers));
    }

    /**
     * Tells whether the server answered 304 to {@code If-None-Match}.
     *
     * @return true on 304, when {@link #data()} is null
     */
    public boolean notModified() {
        return status == 304;
    }

    @Override
    public String toString() {
        return "RaskResponse{status=" + status + ", requestId=" + requestId() + ", data=" + data + "}";
    }
}
