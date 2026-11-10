package ai.rask.sdk.internal;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * A strict RFC 8259 reader and writer over plain Java values, so the SDK has no runtime dependency.
 *
 * <p>Objects decode to unmodifiable {@code Map<String, Object>} in document order, arrays to unmodifiable
 * {@code List<Object>}, numbers to {@link Long}, {@link BigInteger} or {@link Double}. Internal API.
 */
public final class Json {
    /** Deep enough for every contract response, shallow enough that hostile input cannot overflow the stack. */
    private static final int MAX_DEPTH = 256;

    private final String text;
    private int at;

    private Json(String text) {
        this.text = text;
    }

    /**
     * Parses one JSON document.
     *
     * @param text the document
     * @return the decoded value
     * @throws IllegalArgumentException when the text is not valid JSON
     */
    public static Object parse(String text) {
        Json reader = new Json(text);
        reader.skipSpace();
        Object value = reader.readValue(0);
        reader.skipSpace();
        if (reader.at != text.length()) {
            throw reader.error("unexpected content after the document");
        }
        return value;
    }

    /**
     * Writes a value as compact JSON.
     *
     * @param value a map, iterable, string, number, boolean or null
     * @return the JSON text
     */
    public static String write(Object value) {
        StringBuilder out = new StringBuilder();
        writeValue(out, value);
        return out.toString();
    }

    private IllegalArgumentException error(String message) {
        return new IllegalArgumentException("Invalid JSON at offset " + at + ": " + message);
    }

    private void skipSpace() {
        while (at < text.length()) {
            char c = text.charAt(at);
            if (c != ' ' && c != '\n' && c != '\r' && c != '\t') {
                return;
            }
            at++;
        }
    }

    private Object readValue(int depth) {
        if (depth > MAX_DEPTH) {
            throw error("nested too deeply");
        }
        if (at >= text.length()) {
            throw error("unexpected end of input");
        }
        char c = text.charAt(at);
        switch (c) {
            case '{':
                return readObject(depth);
            case '[':
                return readArray(depth);
            case '"':
                return readString();
            case 't':
                return readLiteral("true", Boolean.TRUE);
            case 'f':
                return readLiteral("false", Boolean.FALSE);
            case 'n':
                return readLiteral("null", null);
            default:
                return readNumber();
        }
    }

    private Object readLiteral(String literal, Object value) {
        if (!text.startsWith(literal, at)) {
            throw error("unexpected token");
        }
        at += literal.length();
        return value;
    }

    private Map<String, Object> readObject(int depth) {
        Map<String, Object> result = new LinkedHashMap<>();
        at++;
        skipSpace();
        if (peek() == '}') {
            at++;
            return Collections.unmodifiableMap(result);
        }
        while (true) {
            skipSpace();
            if (peek() != '"') {
                throw error("expected a member name");
            }
            String name = readString();
            skipSpace();
            if (peek() != ':') {
                throw error("expected ':'");
            }
            at++;
            skipSpace();
            result.put(name, readValue(depth + 1));
            skipSpace();
            char c = peek();
            at++;
            if (c == '}') {
                return Collections.unmodifiableMap(result);
            }
            if (c != ',') {
                at--;
                throw error("expected ',' or '}'");
            }
        }
    }

    private List<Object> readArray(int depth) {
        List<Object> result = new ArrayList<>();
        at++;
        skipSpace();
        if (peek() == ']') {
            at++;
            return Collections.unmodifiableList(result);
        }
        while (true) {
            skipSpace();
            result.add(readValue(depth + 1));
            skipSpace();
            char c = peek();
            at++;
            if (c == ']') {
                return Collections.unmodifiableList(result);
            }
            if (c != ',') {
                at--;
                throw error("expected ',' or ']'");
            }
        }
    }

    private char peek() {
        if (at >= text.length()) {
            throw error("unexpected end of input");
        }
        return text.charAt(at);
    }

    private String readString() {
        at++;
        StringBuilder out = null;
        int start = at;
        while (true) {
            char c = peek();
            if (c == '"') {
                String tail = text.substring(start, at);
                at++;
                return out == null ? tail : out.append(tail).toString();
            }
            if (c < 0x20) {
                throw error("unescaped control character in a string");
            }
            if (c != '\\') {
                at++;
                continue;
            }
            if (out == null) {
                out = new StringBuilder();
            }
            out.append(text, start, at);
            at++;
            readEscape(out);
            start = at;
        }
    }

    private void readEscape(StringBuilder out) {
        char c = peek();
        at++;
        switch (c) {
            case '"':
            case '\\':
            case '/':
                out.append(c);
                break;
            case 'b':
                out.append('\b');
                break;
            case 'f':
                out.append('\f');
                break;
            case 'n':
                out.append('\n');
                break;
            case 'r':
                out.append('\r');
                break;
            case 't':
                out.append('\t');
                break;
            case 'u':
                out.append(readHex4());
                break;
            default:
                at--;
                throw error("invalid escape");
        }
    }

    private char readHex4() {
        if (at + 4 > text.length()) {
            throw error("truncated \\u escape");
        }
        int value = 0;
        for (int i = 0; i < 4; i++) {
            int digit = Character.digit(text.charAt(at + i), 16);
            if (digit < 0) {
                throw error("invalid \\u escape");
            }
            value = value * 16 + digit;
        }
        at += 4;
        return (char) value;
    }

    private Number readNumber() {
        int start = at;
        if (peek() == '-') {
            at++;
        }
        char first = peek();
        if (first == '0') {
            at++;
        } else if (first >= '1' && first <= '9') {
            skipDigits();
        } else {
            throw error("unexpected token");
        }
        boolean integer = true;
        if (at < text.length() && text.charAt(at) == '.') {
            integer = false;
            at++;
            requireDigit();
            skipDigits();
        }
        if (at < text.length() && (text.charAt(at) == 'e' || text.charAt(at) == 'E')) {
            integer = false;
            at++;
            if (at < text.length() && (text.charAt(at) == '+' || text.charAt(at) == '-')) {
                at++;
            }
            requireDigit();
            skipDigits();
        }
        String literal = text.substring(start, at);
        if (!integer) {
            Double number = Double.valueOf(literal);
            // 1e999 is valid JSON; keep it writable rather than turning it into Infinity.
            return number.isInfinite() ? (Number) new BigDecimal(literal) : number;
        }
        if (literal.length() < 19) {
            return Long.valueOf(literal);
        }
        BigInteger big = new BigInteger(literal);
        return big.bitLength() < 64 ? (Number) big.longValue() : big;
    }

    private void requireDigit() {
        char c = peek();
        if (c < '0' || c > '9') {
            throw error("expected a digit");
        }
    }

    private void skipDigits() {
        while (at < text.length() && text.charAt(at) >= '0' && text.charAt(at) <= '9') {
            at++;
        }
    }

    private static void writeValue(StringBuilder out, Object value) {
        if (value == null) {
            out.append("null");
        } else if (value instanceof CharSequence) {
            writeString(out, value.toString());
        } else if (value instanceof Boolean) {
            out.append(value);
        } else if (value instanceof Double || value instanceof Float) {
            double number = ((Number) value).doubleValue();
            if (Double.isNaN(number) || Double.isInfinite(number)) {
                throw new IllegalArgumentException("JSON has no representation of " + number);
            }
            out.append(value);
        } else if (value instanceof BigDecimal) {
            out.append(((BigDecimal) value).toString());
        } else if (value instanceof Number) {
            out.append(value);
        } else if (value instanceof Map) {
            writeObject(out, (Map<?, ?>) value);
        } else if (value instanceof Iterable) {
            writeArray(out, (Iterable<?>) value);
        } else {
            throw new IllegalArgumentException("Cannot write " + value.getClass().getName() + " as JSON");
        }
    }

    private static void writeObject(StringBuilder out, Map<?, ?> map) {
        out.append('{');
        boolean first = true;
        for (Map.Entry<?, ?> entry : map.entrySet()) {
            if (!first) {
                out.append(',');
            }
            first = false;
            writeString(out, String.valueOf(entry.getKey()));
            out.append(':');
            writeValue(out, entry.getValue());
        }
        out.append('}');
    }

    private static void writeArray(StringBuilder out, Iterable<?> items) {
        out.append('[');
        boolean first = true;
        for (Object item : items) {
            if (!first) {
                out.append(',');
            }
            first = false;
            writeValue(out, item);
        }
        out.append(']');
    }

    private static void writeString(StringBuilder out, String value) {
        out.append('"');
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            switch (c) {
                case '"':
                    out.append("\\\"");
                    break;
                case '\\':
                    out.append("\\\\");
                    break;
                case '\n':
                    out.append("\\n");
                    break;
                case '\r':
                    out.append("\\r");
                    break;
                case '\t':
                    out.append("\\t");
                    break;
                default:
                    if (c < 0x20 || isLoneSurrogate(value, i)) {
                        out.append(String.format("\\u%04x", (int) c));
                    } else {
                        out.append(c);
                    }
            }
        }
        out.append('"');
    }

    private static boolean isLoneSurrogate(String value, int index) {
        char c = value.charAt(index);
        if (Character.isHighSurrogate(c)) {
            return index + 1 >= value.length() || !Character.isLowSurrogate(value.charAt(index + 1));
        }
        if (Character.isLowSurrogate(c)) {
            return index == 0 || !Character.isHighSurrogate(value.charAt(index - 1));
        }
        return false;
    }
}
