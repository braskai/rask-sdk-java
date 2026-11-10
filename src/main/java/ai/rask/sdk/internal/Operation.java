package ai.rask.sdk.internal;

/**
 * What the transport must know about one contract operation. Internal API.
 */
public final class Operation {
    /** How an operation takes {@code Idempotency-Key}. */
    public enum KeyPolicy {
        /** The contract declares no key: never send one. */
        NONE,
        /** The contract declares a key, required or optional: the SDK always sends one (S4). */
        KEYED
    }

    /** How an operation takes {@code If-Match}. */
    public enum IfMatchPolicy {
        /** The contract declares no {@code If-Match}. */
        NONE,
        /** The caller may send it. */
        OPTIONAL,
        /** The caller must send it; the SDK refuses before the network otherwise. */
        REQUIRED
    }

    private final String id;
    private final String method;
    private final String path;
    private final KeyPolicy keyPolicy;
    private final IfMatchPolicy ifMatchPolicy;
    private final boolean ifNoneMatch;

    /**
     * Describes an operation.
     *
     * @param id the operationId
     * @param method the HTTP method
     * @param path the path template, relative to the base URL
     * @param keyPolicy the idempotency key rule
     * @param ifMatchPolicy the If-Match rule
     * @param ifNoneMatch whether the contract declares If-None-Match
     */
    public Operation(String id, String method, String path, KeyPolicy keyPolicy, IfMatchPolicy ifMatchPolicy,
            boolean ifNoneMatch) {
        this.id = id;
        this.method = method;
        this.path = path;
        this.keyPolicy = keyPolicy;
        this.ifMatchPolicy = ifMatchPolicy;
        this.ifNoneMatch = ifNoneMatch;
    }

    /**
     * Returns the operationId.
     *
     * @return the operationId
     */
    public String id() {
        return id;
    }

    /**
     * Returns the HTTP method.
     *
     * @return the HTTP method
     */
    public String method() {
        return method;
    }

    /**
     * Returns the path template.
     *
     * @return the path template, relative to the base URL
     */
    public String path() {
        return path;
    }

    /**
     * Returns the idempotency key rule.
     *
     * @return the rule
     */
    public KeyPolicy keyPolicy() {
        return keyPolicy;
    }

    /**
     * Returns the If-Match rule.
     *
     * @return the rule
     */
    public IfMatchPolicy ifMatchPolicy() {
        return ifMatchPolicy;
    }

    /**
     * Tells whether the contract declares If-None-Match.
     *
     * @return true when it does
     */
    public boolean takesIfNoneMatch() {
        return ifNoneMatch;
    }
}
