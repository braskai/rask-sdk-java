package ai.rask.sdk;

import ai.rask.sdk.model.OpenEnum;
import ai.rask.sdk.model.RaskModel;
import ai.rask.sdk.model.RaskValue;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Calls every typed getter of a decoded value, recursively: an unknown value must not make any of them throw. */
final class ModelWalker {
    private static final Set<String> NOT_GETTERS = Set.of("raw", "toJson", "hashCode", "toString", "getClass", "notify",
            "notifyAll", "wait", "builder");

    int models;
    int unknownEnums;
    int unknownVariants;

    /**
     * Follows a pointer through the typed getters, as an application would, to where an unknown value was injected.
     *
     * @return what is wrong, or null
     */
    static String checkTyped(Object root, String pointer, String kind) throws ReflectiveOperationException {
        Object node = root;
        String[] parts = pointer.equals("/") ? new String[0] : pointer.substring(1).split("/");
        for (int i = 0; i < parts.length; i++) {
            boolean last = i == parts.length - 1;
            if ("field".equals(kind) && last) {
                return node instanceof RaskValue && ((RaskValue) node).get(parts[i]) != null ? null
                        : "the unknown field " + pointer + " is not readable with get()";
            }
            if (node instanceof List) {
                node = ((List<?>) node).get(Integer.parseInt(parts[i]));
            } else if (node instanceof RaskValue) {
                Method getter;
                try {
                    getter = node.getClass().getMethod(TypedCalls.camel(parts[i]));
                } catch (NoSuchMethodException e) {
                    return node.getClass().getSimpleName() + " has no getter for " + parts[i] + " at " + pointer;
                }
                node = getter.invoke(node);
            } else {
                return "cannot follow " + pointer + " past " + parts[i] + ": " + node;
            }
        }
        if ("enum".equals(kind)) {
            return node instanceof OpenEnum && !((OpenEnum) node).isKnown() ? null
                    : "the unknown enum at " + pointer + " reads as " + node;
        }
        if ("union".equals(kind)) {
            return node instanceof RaskValue && "Unknown".equals(node.getClass().getSimpleName()) ? null
                    : "the unknown variant at " + pointer + " reads as " + node;
        }
        return null;
    }

    void walk(Object value) throws ReflectiveOperationException {
        if (value instanceof OpenEnum) {
            if (!((OpenEnum) value).isKnown()) {
                unknownEnums++;
            }
        } else if (value instanceof RaskValue) {
            walkModel((RaskValue) value);
        } else if (value instanceof Collection) {
            for (Object item : (Collection<?>) value) {
                walk(item);
            }
        } else if (value instanceof Map) {
            for (Object item : ((Map<?, ?>) value).values()) {
                walk(item);
            }
        }
    }

    private void walkModel(RaskValue model) throws ReflectiveOperationException {
        models++;
        if (model.getClass().getSimpleName().equals("Unknown")) {
            unknownVariants++;
        }
        for (Method method : model.getClass().getMethods()) {
            if (method.getParameterCount() != 0 || Modifier.isStatic(method.getModifiers())
                    || NOT_GETTERS.contains(method.getName()) || method.getDeclaringClass() == Object.class
                    || method.getDeclaringClass() == RaskModel.class) {
                continue;
            }
            try {
                walk(method.invoke(model));
            } catch (InvocationTargetException e) {
                throw new IllegalStateException(model.getClass().getSimpleName() + "." + method.getName()
                        + "() threw on " + model.toJson(), e.getCause());
            }
        }
    }
}
