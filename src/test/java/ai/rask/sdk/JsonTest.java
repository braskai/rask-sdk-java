package ai.rask.sdk;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import ai.rask.sdk.internal.Json;
import java.math.BigInteger;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class JsonTest {
    @Test
    void readsEveryJsonType() {
        Object value = Json.parse(" {\"a\": [1, -2.5, 1e3, true, false, null, \"x\"], \"b\": {}} ");
        Map<?, ?> object = (Map<?, ?>) value;
        assertEquals(Arrays.asList(1L, -2.5, 1000.0, true, false, null, "x"), object.get("a"));
        assertEquals(Map.of(), object.get("b"));
    }

    @Test
    void keepsLargeIntegersExactly() {
        assertEquals(5000000000L, Json.parse("5000000000"));
        assertEquals(Long.MAX_VALUE, Json.parse("9223372036854775807"));
        assertEquals(new BigInteger("9223372036854775808"), Json.parse("9223372036854775808"));
    }

    @Test
    void readsStringEscapesAndSurrogates() {
        assertEquals("a\"\\/\b\f\n\r\t\u00e9\uD83D\uDE00",
                Json.parse("\"a\\\"\\\\\\/\\b\\f\\n\\r\\t\\u00e9\\ud83d\\ude00\""));
        assertEquals("héllo 😀", Json.parse("\"héllo 😀\""));
    }

    @Test
    void writesWhatItReads() {
        String text = "{\"a\":[1,-2.5,true,false,null,\"x\\n\\\"y\\\"\"],\"b\":{\"c\":\"\"},\"é\":\"😀\"}";
        assertEquals(text, Json.write(Json.parse(text)));
    }

    @Test
    void escapesControlCharactersAndLoneSurrogates() {
        assertEquals("\"\\u0001\"", Json.write("\u0001"));
        assertEquals("\"\\ud83d\"", Json.write("\uD83D"));
    }

    @Test
    void refusesWhatRfc8259Refuses() {
        for (String bad : List.of("", "{", "[1,]", "{\"a\":1,}", "01", "1.", ".5", "+1", "nul", "\"\n\"", "\"\\x\"",
                "\"\\u12\"", "1 2", "{\"a\" 1}", "[1 2]", "'a'")) {
            assertThrows(IllegalArgumentException.class, () -> Json.parse(bad), bad);
        }
    }

    @Test
    void refusesNestingThatWouldOverflowTheStack() {
        String deep = "[".repeat(100_000);
        assertThrows(IllegalArgumentException.class, () -> Json.parse(deep));
    }

    @Test
    void keepsNumbersADoubleCannotHold() {
        assertEquals("1E+999", Json.write(Json.parse("1e999")));
    }

    @Test
    void refusesNumbersJsonCannotHold() {
        assertThrows(IllegalArgumentException.class, () -> Json.write(Double.NaN));
    }
}
