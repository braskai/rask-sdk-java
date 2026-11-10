package ai.rask.sdk.model;

import ai.rask.sdk.internal.Wire;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Base of request builders. A field that was not set is omitted and a null clears a previous set: requests never send
 * JSON null. An empty string is sent as an empty string, which is how the API clears a field.
 *
 * @param <B> the concrete builder
 */
public abstract class ModelBuilder<B extends ModelBuilder<B>> {
    private final Map<String, Object> values = new LinkedHashMap<>();

    /** Creates an empty builder. */
    protected ModelBuilder() {
    }

    /**
     * Sets or clears a field.
     *
     * @param name the JSON name
     * @param value the value, or null to omit it
     * @return this builder
     */
    @SuppressWarnings("unchecked")
    protected final B set(String name, Object value) {
        if (value == null) {
            values.remove(name);
        } else {
            values.put(name, Wire.toJson(value));
        }
        return (B) this;
    }

    /**
     * Checks the fields the contract requires.
     *
     * @param type the schema name for the error message
     * @param names the required JSON names
     * @throws IllegalArgumentException when one is missing
     */
    protected final void require(String type, String... names) {
        for (String name : names) {
            if (!values.containsKey(name)) {
                throw new IllegalArgumentException(type + " requires " + name);
            }
        }
    }

    /**
     * Checks the contract's minProperties.
     *
     * @param type the schema name for the error message
     * @param count the minimum number of fields
     * @throws IllegalArgumentException when fewer are set
     */
    protected final void requireAtLeast(String type, int count) {
        if (values.size() < count) {
            throw new IllegalArgumentException(type + " requires at least " + count + " field(s)");
        }
    }

    /**
     * Returns a copy of the fields set so far.
     *
     * @return the JSON object to wrap
     */
    protected final Map<String, Object> values() {
        return new LinkedHashMap<>(values);
    }
}
