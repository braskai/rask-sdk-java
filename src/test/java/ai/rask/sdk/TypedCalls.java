package ai.rask.sdk;

import ai.rask.sdk.internal.Json;
import ai.rask.sdk.model.OpenEnum;
import ai.rask.sdk.model.RaskValue;
import ai.rask.sdk.params.QueryParams;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Runs a scenario's call through the SDK's public, typed surface: the resource method named after the operationId, the
 * generated builders and the query parameter classes. Scenarios are data, so this goes by reflection, but nothing here
 * reaches behind the API an application uses.
 */
final class TypedCalls {
    private static final Pattern PATH_PARAM = Pattern.compile("\\{(\\w+)\\}");

    private final Rask rask;
    private final Map<String, Map<String, Object>> operations = new TreeMap<>();

    TypedCalls(Rask rask, List<Map<String, Object>> operationList) {
        this.rask = rask;
        for (Map<String, Object> operation : operationList) {
            operations.put((String) operation.get("operationId"), operation);
        }
    }

    /** Every resource object, by reflection over the client's accessors. */
    static List<Object> resources(Rask rask) throws ReflectiveOperationException {
        List<Object> found = new ArrayList<>();
        for (Method accessor : RaskResources.class.getDeclaredMethods()) {
            if (Modifier.isPublic(accessor.getModifiers()) && accessor.getParameterCount() == 0) {
                found.add(accessor.invoke(rask));
            }
        }
        return found;
    }

    /** The resource method for an operation: named after the operationId, the overload that takes options. */
    static Method fullMethod(Object resource, String operationId) {
        for (Method method : resource.getClass().getMethods()) {
            Class<?>[] types = method.getParameterTypes();
            if (method.getName().equals(operationId) && types.length > 0
                    && types[types.length - 1] == RequestOptions.class) {
                return method;
            }
        }
        return null;
    }

    private Object resourceWith(String method) throws ReflectiveOperationException {
        for (Object resource : resources(rask)) {
            if (fullMethod(resource, method) != null) {
                return resource;
            }
            for (Method candidate : resource.getClass().getMethods()) {
                if (candidate.getName().equals(method)) {
                    return resource;
                }
            }
        }
        throw new IllegalStateException("No resource has " + method);
    }

    private static List<String> pathNames(Map<String, Object> operation) {
        List<String> names = new ArrayList<>();
        Matcher matcher = PATH_PARAM.matcher((String) operation.get("path"));
        while (matcher.find()) {
            names.add(matcher.group(1));
        }
        return names;
    }

    RaskResponse<?> call(String operationId, Map<String, Object> path, Map<String, Object> query,
            Map<String, Object> body, String ifMatch, String idempotencyKey) throws Throwable {
        Map<String, Object> operation = operations.get(operationId);
        Object resource = resourceWith(operationId);
        Method method = fullMethod(resource, operationId);
        Object[] args = arguments(method, operation, path, query, body, ifMatch, idempotencyKey);
        return (RaskResponse<?>) unwrap(method, resource, args);
    }

    /** The auto-paging iterator of a list operation. */
    Iterable<?> iterate(String operationId, Map<String, Object> path, Map<String, Object> query) throws Throwable {
        Map<String, Object> operation = operations.get(operationId);
        String walk = operationId + "AutoPaging";
        Object resource = resourceWith(walk);
        for (Method method : resource.getClass().getMethods()) {
            Class<?>[] types = method.getParameterTypes();
            if (method.getName().equals(walk) && types.length > 0 && types[types.length - 1] == RequestOptions.class) {
                Object[] args = arguments(method, operation, path, query, null, null, null);
                return (Iterable<?>) unwrap(method, resource, args);
            }
        }
        throw new IllegalStateException("No auto-paging method " + walk);
    }

    private static Object unwrap(Method method, Object target, Object[] args) throws Throwable {
        try {
            return method.invoke(target, args);
        } catch (InvocationTargetException e) {
            throw e.getCause();
        }
    }

    private Object[] arguments(Method method, Map<String, Object> operation, Map<String, Object> path,
            Map<String, Object> query, Map<String, Object> body, String ifMatch, String idempotencyKey)
            throws ReflectiveOperationException {
        List<String> names = pathNames(operation);
        Type[] types = method.getGenericParameterTypes();
        Object[] args = new Object[types.length];
        boolean positionalIfMatch = false;
        for (int i = 0; i < types.length; i++) {
            Class<?> type = method.getParameterTypes()[i];
            if (i < names.size()) {
                Object value = path == null ? null : path.get(names.get(i));
                args[i] = type == int.class ? ((Number) value).intValue() : String.valueOf(value);
            } else if (type == String.class) {
                positionalIfMatch = true;
                args[i] = ifMatch;
            } else if (RaskValue.class.isAssignableFrom(type)) {
                args[i] = body == null ? null : request(type, body);
            } else if (QueryParams.class.isAssignableFrom(type)) {
                args[i] = queryParams(type, query);
            } else if (type == RequestOptions.class) {
                RequestOptions.Builder options = RequestOptions.builder();
                if (idempotencyKey != null) {
                    options.idempotencyKey(idempotencyKey);
                }
                if (!positionalIfMatch && ifMatch != null) {
                    options.ifMatch(ifMatch);
                }
                args[i] = options.build();
            } else {
                throw new IllegalStateException("Unexpected parameter " + type + " on " + method);
            }
        }
        return args;
    }

    /** Builds a request through its builder; a member the builder cannot set is a failure. */
    static Object request(Class<?> type, Map<String, Object> json) throws ReflectiveOperationException {
        Class<?> concrete = type;
        if (type.isInterface()) {
            concrete = type.getMethod("of", Map.class).invoke(null, json).getClass();
        }
        Object builder = concrete.getMethod("builder").invoke(null);
        for (Map.Entry<String, Object> entry : json.entrySet()) {
            Method setter = setter(builder, camel(entry.getKey()));
            if (setter == null) {
                continue;
            }
            setter.invoke(builder, coerce(setter.getGenericParameterTypes()[0], entry.getValue()));
        }
        Object built;
        try {
            built = builder.getClass().getMethod("build").invoke(builder);
        } catch (InvocationTargetException e) {
            throw new IllegalStateException("build() of " + concrete.getSimpleName() + ": " + e.getCause(), e);
        }
        if (!JsonEq.same(((RaskValue) built).raw(), json)) {
            throw new IllegalStateException(concrete.getSimpleName() + " built " + ((RaskValue) built).toJson()
                    + " from " + Json.write(json));
        }
        return built;
    }

    private static Object queryParams(Class<?> type, Map<String, Object> query) throws ReflectiveOperationException {
        if (query == null || query.isEmpty()) {
            return null;
        }
        Object builder = type.getMethod("builder").invoke(null);
        for (Map.Entry<String, Object> entry : query.entrySet()) {
            Method setter = setter(builder, camel(entry.getKey()));
            if (setter == null) {
                throw new IllegalStateException(type.getSimpleName() + " has no setter for " + entry.getKey());
            }
            setter.invoke(builder, coerce(setter.getGenericParameterTypes()[0], entry.getValue()));
        }
        return builder.getClass().getMethod("build").invoke(builder);
    }

    private static Method setter(Object builder, String name) {
        for (Method method : builder.getClass().getMethods()) {
            if (method.getName().equals(name) && method.getParameterCount() == 1) {
                return method;
            }
        }
        return null;
    }

    @SuppressWarnings("unchecked")
    private static Object coerce(Type type, Object json) throws ReflectiveOperationException {
        if (type instanceof ParameterizedType) {
            ParameterizedType generic = (ParameterizedType) type;
            if (generic.getRawType() == Map.class) {
                return json;
            }
            List<Object> items = new ArrayList<>();
            List<?> source = json instanceof String ? Arrays.asList(((String) json).split(",")) : (List<?>) json;
            for (Object item : source) {
                items.add(coerce(generic.getActualTypeArguments()[0], item));
            }
            return items;
        }
        Class<?> raw = (Class<?>) type;
        if (raw == String.class) {
            return json;
        }
        if (raw == int.class) {
            return json instanceof String ? Integer.valueOf((String) json)
                    : Integer.valueOf(((Number) json).intValue());
        }
        if (raw == long.class) {
            return json instanceof String ? Long.valueOf((String) json) : Long.valueOf(((Number) json).longValue());
        }
        if (raw == double.class) {
            return json instanceof String ? Double.valueOf((String) json)
                    : Double.valueOf(((Number) json).doubleValue());
        }
        if (raw == boolean.class) {
            return json instanceof String ? Boolean.valueOf((String) json) : json;
        }
        if (raw == OffsetDateTime.class) {
            return OffsetDateTime.parse((String) json);
        }
        if (raw == LocalDate.class) {
            return LocalDate.parse((String) json);
        }
        if (OpenEnum.class.isAssignableFrom(raw)) {
            return raw.getMethod("of", String.class).invoke(null, json);
        }
        if (RaskValue.class.isAssignableFrom(raw)) {
            return request(raw, (Map<String, Object>) json);
        }
        throw new IllegalStateException("Cannot coerce to " + type);
    }

    static String camel(String name) {
        StringBuilder out = new StringBuilder();
        boolean upper = false;
        for (char c : name.toCharArray()) {
            if (c == '_' || c == '-') {
                upper = true;
            } else {
                out.append(upper ? Character.toUpperCase(c) : c);
                upper = false;
            }
        }
        return out.length() == 0 ? name : out.substring(0, 1).toLowerCase(Locale.ROOT) + out.substring(1);
    }
}
