package ai.rask.sdk;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ai.rask.sdk.errors.RaskError;
import java.util.ArrayList;
import java.util.List;
import java.util.function.IntFunction;
import org.junit.jupiter.api.Test;

/** A server that hands out a cursor twice: following it would fetch the same pages for ever. */
class PageIterableTest {
    /** Walks a list whose page number n answers with the cursor that {@code next} gives, one item a page. */
    private static RaskError walk(IntFunction<String> next, List<String> seen, List<String> asked) {
        PageIterable<String> pages = PageIterable.<Integer, String>of(cursor -> {
            asked.add(String.valueOf(cursor));
            return new RaskResponse<>(200, null, asked.size(), null);
        }, number -> List.of("item-" + number), number -> next.apply(number));
        return assertThrows(RaskError.class, () -> {
            for (String item : pages) {
                seen.add(item);
                // Without the check the walk has no end, and this test has to have one.
                if (seen.size() == 10) {
                    break;
                }
            }
        });
    }

    @Test
    void aPageThatAnswersWithTheCursorItWasAskedForWithIsAnError() {
        List<String> seen = new ArrayList<>();
        List<String> asked = new ArrayList<>();
        RaskError error = walk(number -> "c2", seen, asked);
        assertTrue(error.getMessage().contains("cursor"), error.getMessage());
        assertEquals(200, error.status());
        assertEquals(List.of("item-1", "item-2"), seen);
        assertEquals(List.of("null", "c2"), asked);
    }

    /** A cycle repeats a cursor too, though never twice in a row: a, b, then a again. */
    @Test
    void cursorsThatGoRoundInACycleAreAnError() {
        List<String> seen = new ArrayList<>();
        List<String> asked = new ArrayList<>();
        RaskError error = walk(number -> number % 2 == 1 ? "a" : "b", seen, asked);
        assertTrue(error.getMessage().contains("cursor"), error.getMessage());
        assertEquals(List.of("item-1", "item-2", "item-3"), seen);
        assertEquals(List.of("null", "a", "b"), asked);
    }
}
