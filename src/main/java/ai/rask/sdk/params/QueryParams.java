package ai.rask.sdk.params;

import ai.rask.sdk.internal.Wire;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/** Base of the query parameter classes: what an operation's optional filters and paging arguments put on the URL. */
public abstract class QueryParams {
    private final Map<String, Object> values;

    /**
     * Creates the parameters.
     *
     * @param builder the builder holding them
     */
    protected QueryParams(Builder<?> builder) {
        this.values = Collections.unmodifiableMap(new LinkedHashMap<>(builder.values));
    }

    /**
     * Returns the parameters by wire name, unencoded. A list is one comma-separated query value.
     *
     * @return an unmodifiable map whose values are strings or lists of strings
     */
    public final Map<String, Object> values() {
        return values;
    }

    /**
     * Base of the generated builders.
     *
     * @param <B> the concrete builder
     */
    public abstract static class Builder<B extends Builder<B>> {
        private final Map<String, Object> values = new LinkedHashMap<>();

        /** Creates an empty builder. */
        protected Builder() {
        }

        /**
         * Sets or removes a parameter.
         *
         * @param name the wire name
         * @param value the value, or null to omit it
         * @return this builder
         */
        @SuppressWarnings("unchecked")
        protected final B set(String name, Object value) {
            if (value == null) {
                values.remove(name);
            } else {
                values.put(name, Wire.toQuery(value));
            }
            return (B) this;
        }

        /**
         * Checks the parameters the contract requires.
         *
         * @param operationId the operation for the error message
         * @param names the required wire names
         * @throws IllegalArgumentException when one is missing
         */
        protected final void require(String operationId, String... names) {
            for (String name : names) {
                if (!values.containsKey(name)) {
                    throw new IllegalArgumentException(operationId + " requires the query parameter " + name);
                }
            }
        }
    }
}
