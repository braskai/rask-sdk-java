package ai.rask.sdk.model;

import java.util.Map;

/** Anything decoded from, or sent as, a JSON object: a model or a union variant. */
public interface RaskValue {
    /**
     * Returns every member exactly as the server sent it, known to this SDK version or not.
     *
     * @return an unmodifiable view of the JSON object
     */
    Map<String, Object> raw();

    /**
     * Reads one member by its JSON name, including members this SDK version does not know.
     *
     * @param name the JSON member name
     * @return the decoded JSON value, or null when absent
     */
    Object get(String name);

    /**
     * Reads a value by JSON Pointer (RFC 6901), for example {@code /params/type}.
     *
     * @param pointer the pointer; an empty string or {@code /} is the object itself
     * @return the decoded JSON value, or null when absent
     */
    Object pointer(String pointer);

    /**
     * Returns the exact JSON of this value.
     *
     * @return compact JSON; unlike {@link Object#toString()} it does not mask presigned URLs
     */
    String toJson();
}
