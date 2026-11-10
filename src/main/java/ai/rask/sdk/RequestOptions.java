package ai.rask.sdk;

import java.time.Duration;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

/** Per-call settings; every operation has an overload that takes these. */
public final class RequestOptions {
    private final String idempotencyKey;
    private final String ifMatch;
    private final String ifNoneMatch;
    private final Duration timeout;
    private final Duration retryBudget;
    private final Map<String, String> headers;

    private RequestOptions(Builder builder) {
        this.idempotencyKey = builder.idempotencyKey;
        this.ifMatch = builder.ifMatch;
        this.ifNoneMatch = builder.ifNoneMatch;
        this.timeout = builder.timeout;
        this.retryBudget = builder.retryBudget;
        this.headers = Collections.unmodifiableMap(new LinkedHashMap<>(builder.headers));
    }

    /**
     * Starts the options.
     *
     * @return a new builder
     */
    public static Builder builder() {
        return new Builder();
    }

    /**
     * Returns the caller's idempotency key.
     *
     * @return the key, or null to have one generated
     */
    public String idempotencyKey() {
        return idempotencyKey;
    }

    /**
     * Returns the optional {@code If-Match}.
     *
     * @return the ETag, or null
     */
    public String ifMatch() {
        return ifMatch;
    }

    /**
     * Returns the {@code If-None-Match}.
     *
     * @return the ETag, or null
     */
    public String ifNoneMatch() {
        return ifNoneMatch;
    }

    /**
     * Returns the timeout of each attempt.
     *
     * @return the timeout, or null for the client's
     */
    public Duration timeout() {
        return timeout;
    }

    /**
     * Returns the time retries of this call may take.
     *
     * @return the budget, or null for the client's
     */
    public Duration retryBudget() {
        return retryBudget;
    }

    /**
     * Returns the extra headers.
     *
     * @return an unmodifiable map
     */
    public Map<String, String> headers() {
        return headers;
    }

    /** Builds {@link RequestOptions}. */
    public static final class Builder {
        private String idempotencyKey;
        private String ifMatch;
        private String ifNoneMatch;
        private Duration timeout;
        private Duration retryBudget;
        private final Map<String, String> headers = new LinkedHashMap<>();

        private Builder() {
        }

        /**
         * Sends this key instead of a generated UUIDv4, also on retries. Reuse a key to replay the first response.
         *
         * @param value the key
         * @return this builder
         */
        public Builder idempotencyKey(String value) {
            this.idempotencyKey = value;
            return this;
        }

        /**
         * Sends {@code If-Match} on an operation where it is optional. Operations that require it take it as an
         * argument instead. Pass the ETag exactly as read, quotes included.
         *
         * @param value the ETag
         * @return this builder
         */
        public Builder ifMatch(String value) {
            this.ifMatch = value;
            return this;
        }

        /**
         * Sends {@code If-None-Match}; the call then returns a 304 result when nothing changed.
         *
         * @param value the ETag of the cached copy
         * @return this builder
         */
        public Builder ifNoneMatch(String value) {
            this.ifNoneMatch = value;
            return this;
        }

        /**
         * Overrides the client's timeout of each attempt.
         *
         * @param value the timeout
         * @return this builder
         */
        public Builder timeout(Duration value) {
            this.timeout = value;
            return this;
        }

        /**
         * Overrides the client's retry budget for this call; {@link Duration#ZERO} turns retries off.
         *
         * @param value the budget
         * @return this builder
         */
        public Builder retryBudget(Duration value) {
            this.retryBudget = value;
            return this;
        }

        /**
         * Adds a header. {@code Authorization} cannot be set here: the key is the client's.
         *
         * @param name the header name
         * @param value the header value
         * @return this builder
         */
        public Builder header(String name, String value) {
            if ("authorization".equals(name.toLowerCase(Locale.ROOT))) {
                throw new IllegalArgumentException("Authorization comes from the client's API key");
            }
            headers.put(name, value);
            return this;
        }

        /**
         * Finishes the options.
         *
         * @return the options
         */
        public RequestOptions build() {
            return new RequestOptions(this);
        }
    }
}
