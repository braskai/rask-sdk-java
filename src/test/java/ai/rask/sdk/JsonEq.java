package ai.rask.sdk;

import java.math.BigDecimal;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

/** JSON equality: 1 and 1.0 are the same number, as they are on the wire. */
final class JsonEq {
    private JsonEq() {
    }

    static boolean same(Object a, Object b) {
        if (a instanceof Number && b instanceof Number) {
            return new BigDecimal(a.toString()).compareTo(new BigDecimal(b.toString())) == 0;
        }
        if (a instanceof Map && b instanceof Map) {
            Map<?, ?> left = (Map<?, ?>) a;
            Map<?, ?> right = (Map<?, ?>) b;
            if (left.size() != right.size()) {
                return false;
            }
            for (Map.Entry<?, ?> entry : left.entrySet()) {
                if (!right.containsKey(entry.getKey()) || !same(entry.getValue(), right.get(entry.getKey()))) {
                    return false;
                }
            }
            return true;
        }
        if (a instanceof List && b instanceof List) {
            List<?> left = (List<?>) a;
            List<?> right = (List<?>) b;
            if (left.size() != right.size()) {
                return false;
            }
            Iterator<?> other = right.iterator();
            for (Object item : left) {
                if (!same(item, other.next())) {
                    return false;
                }
            }
            return true;
        }
        return a == null ? b == null : a.equals(b);
    }
}
