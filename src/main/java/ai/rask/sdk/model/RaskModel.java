package ai.rask.sdk.model;

import ai.rask.sdk.internal.Json;
import ai.rask.sdk.internal.Redaction;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

/**
 * A view over a decoded JSON object. Getters read from the JSON on demand, so a value this SDK version does not expect
 * can never fail a response: unknown members stay in {@link #raw()}, unknown enum values stay in {@link OpenEnum},
 * unknown union variants become the union's {@code Unknown} class, and a member of an unexpected type reads as null
 * while staying readable through {@link #get(String)}.
 */
public abstract class RaskModel implements RaskValue {
    private final Map<String, Object> raw;

    /**
     * Wraps a JSON object.
     *
     * @param raw the decoded object
     */
    protected RaskModel(Map<String, Object> raw) {
        this.raw = raw == null ? Collections.emptyMap() : Collections.unmodifiableMap(raw);
    }

    @Override
    public final Map<String, Object> raw() {
        return raw;
    }

    @Override
    public final Object get(String name) {
        return raw.get(name);
    }

    @Override
    public final Object pointer(String pointer) {
        if (pointer.isEmpty() || "/".equals(pointer)) {
            return raw;
        }
        Object node = raw;
        for (String part : pointer.substring(1).split("/", -1)) {
            String name = part.replace("~1", "/").replace("~0", "~");
            if (node instanceof Map) {
                node = ((Map<?, ?>) node).get(name);
            } else if (node instanceof List && name.matches("\\d{1,9}")) {
                List<?> list = (List<?>) node;
                int index = Integer.parseInt(name);
                node = index < list.size() ? list.get(index) : null;
            } else {
                return null;
            }
        }
        return node;
    }

    @Override
    public final String toJson() {
        return Json.write(raw);
    }

    @Override
    public boolean equals(Object other) {
        return other != null && other.getClass() == getClass() && raw.equals(((RaskModel) other).raw);
    }

    @Override
    public int hashCode() {
        return raw.hashCode();
    }

    @Override
    public String toString() {
        return getClass().getSimpleName() + Redaction.text(toJson());
    }

    /**
     * Reads a string member.
     *
     * @param name the JSON name
     * @return the value, or null
     */
    protected final String str(String name) {
        Object value = raw.get(name);
        return value instanceof String ? (String) value : null;
    }

    /**
     * Reads an int32 member.
     *
     * @param name the JSON name
     * @return the value, or null when absent, of another type or out of range
     */
    protected final Integer i32(String name) {
        Long value = i64(name);
        return value != null && value >= Integer.MIN_VALUE && value <= Integer.MAX_VALUE ? value.intValue() : null;
    }

    /**
     * Reads an int64 member.
     *
     * @param name the JSON name
     * @return the value, or null; 20.0 reads as 20, because JSON has one number type
     */
    protected final Long i64(String name) {
        Object value = raw.get(name);
        if (value instanceof Double && (Double) value == Math.rint((Double) value)
                && Math.abs((Double) value) < 1e15) {
            return ((Double) value).longValue();
        }
        return value instanceof Long ? (Long) value : null;
    }

    /**
     * Reads a number member.
     *
     * @param name the JSON name
     * @return the value, or null
     */
    protected final Double num(String name) {
        Object value = raw.get(name);
        return value instanceof Number ? ((Number) value).doubleValue() : null;
    }

    /**
     * Reads a boolean member.
     *
     * @param name the JSON name
     * @return the value, or null
     */
    protected final Boolean bool(String name) {
        Object value = raw.get(name);
        return value instanceof Boolean ? (Boolean) value : null;
    }

    /**
     * Reads an RFC 3339 date-time member.
     *
     * @param name the JSON name
     * @return the value, or null when absent or not parseable
     */
    protected final OffsetDateTime time(String name) {
        String value = str(name);
        if (value == null) {
            return null;
        }
        try {
            return OffsetDateTime.parse(value);
        } catch (DateTimeParseException e) {
            return null;
        }
    }

    /**
     * Reads an RFC 3339 full-date member.
     *
     * @param name the JSON name
     * @return the value, or null when absent or not parseable
     */
    protected final LocalDate day(String name) {
        String value = str(name);
        if (value == null) {
            return null;
        }
        try {
            return LocalDate.parse(value);
        } catch (DateTimeParseException e) {
            return null;
        }
    }

    /**
     * Reads an open enum member.
     *
     * @param <T> the enum class
     * @param name the JSON name
     * @param of the enum's factory
     * @return the value, or null
     */
    protected final <T> T enm(String name, Function<String, T> of) {
        String value = str(name);
        return value == null ? null : of.apply(value);
    }

    /**
     * Reads an object member.
     *
     * @param <T> the model class
     * @param name the JSON name
     * @param of the model's factory
     * @return the value, or null
     */
    protected final <T> T obj(String name, Function<Map<String, Object>, T> of) {
        Object value = raw.get(name);
        return value instanceof Map ? of.apply(asObject(value)) : null;
    }

    /**
     * Reads an array of objects.
     *
     * @param <T> the model class
     * @param name the JSON name
     * @param of the model's factory
     * @return the items, or null; an item that is not an object reads as null
     */
    protected final <T> List<T> objs(String name, Function<Map<String, Object>, T> of) {
        Object value = raw.get(name);
        if (!(value instanceof List)) {
            return null;
        }
        List<T> items = new ArrayList<>();
        for (Object item : (List<?>) value) {
            items.add(item instanceof Map ? of.apply(asObject(item)) : null);
        }
        return Collections.unmodifiableList(items);
    }

    /**
     * Reads an array of strings.
     *
     * @param name the JSON name
     * @return the items, or null; an item that is not a string reads as null
     */
    protected final List<String> strs(String name) {
        return enms(name, Function.identity());
    }

    /**
     * Reads an array of open enum values.
     *
     * @param <T> the enum class
     * @param name the JSON name
     * @param of the enum's factory
     * @return the items, or null
     */
    protected final <T> List<T> enms(String name, Function<String, T> of) {
        Object value = raw.get(name);
        if (!(value instanceof List)) {
            return null;
        }
        List<T> items = new ArrayList<>();
        for (Object item : (List<?>) value) {
            items.add(item instanceof String ? of.apply((String) item) : null);
        }
        return Collections.unmodifiableList(items);
    }

    /**
     * Reads an object of strings.
     *
     * @param name the JSON name
     * @return the entries, or null; only string values are included
     */
    protected final Map<String, String> strMap(String name) {
        Object value = raw.get(name);
        return value instanceof Map ? stringsOf(asObject(value)) : null;
    }

    /**
     * Copies the string members of a JSON object.
     *
     * @param object the JSON object
     * @return an unmodifiable map of its string members
     */
    protected static Map<String, String> stringsOf(Map<String, Object> object) {
        Map<String, String> entries = new LinkedHashMap<>();
        for (Map.Entry<String, Object> entry : object.entrySet()) {
            if (entry.getValue() instanceof String) {
                entries.put(entry.getKey(), (String) entry.getValue());
            }
        }
        return Collections.unmodifiableMap(entries);
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> asObject(Object value) {
        return (Map<String, Object>) value;
    }
}
