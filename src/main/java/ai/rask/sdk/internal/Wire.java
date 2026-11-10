package ai.rask.sdk.internal;

import ai.rask.sdk.model.OpenEnum;
import ai.rask.sdk.model.RaskValue;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/** Converts typed request values to JSON values and query strings. Internal API. */
public final class Wire {
    private Wire() {
    }

    /**
     * Converts a typed value to a JSON value, copying collections.
     *
     * @param value a scalar, enum, model, date, list or map
     * @return the JSON value
     */
    public static Object toJson(Object value) {
        if (value instanceof OpenEnum) {
            return ((OpenEnum) value).value();
        }
        if (value instanceof RaskValue) {
            return ((RaskValue) value).raw();
        }
        if (value instanceof OffsetDateTime) {
            return DateTimeFormatter.ISO_OFFSET_DATE_TIME.format((OffsetDateTime) value);
        }
        if (value instanceof LocalDate) {
            return value.toString();
        }
        if (value instanceof Map) {
            Map<String, Object> copy = new LinkedHashMap<>();
            for (Map.Entry<?, ?> entry : ((Map<?, ?>) value).entrySet()) {
                copy.put(String.valueOf(entry.getKey()), toJson(entry.getValue()));
            }
            return Collections.unmodifiableMap(copy);
        }
        if (value instanceof Iterable) {
            List<Object> copy = new ArrayList<>();
            for (Object item : (Iterable<?>) value) {
                copy.add(toJson(item));
            }
            return Collections.unmodifiableList(copy);
        }
        return value;
    }

    /**
     * Converts a typed value to its query string form: arrays are one comma-separated value (style form, explode
     * false). The result is not yet percent-encoded; lists stay lists so the comma stays a literal delimiter.
     *
     * @param value a scalar, enum, date or list
     * @return the value as text, or a list of texts
     */
    public static Object toQuery(Object value) {
        Object json = toJson(value);
        if (json instanceof List) {
            return ((List<?>) json).stream().map(String::valueOf).collect(Collectors.toUnmodifiableList());
        }
        return String.valueOf(json);
    }
}
