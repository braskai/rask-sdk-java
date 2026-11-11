package ai.rask.sdk;

import ai.rask.sdk.errors.RaskError;
import java.net.http.HttpHeaders;
import java.util.Collections;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Stream;
import java.util.stream.StreamSupport;

/**
 * Walks every item of a list, fetching the next page over {@code next_cursor} only when the items so far are used up.
 * Each {@link #iterator()} starts again from the first page. A short or empty page does not end the walk; only a null
 * {@code next_cursor} does. A cursor the server has already given on the walk raises a {@link RaskError}, once the
 * items of its page are used up: following it would never end.
 *
 * @param <T> the item type
 */
public final class PageIterable<T> implements Iterable<T> {
    private final Function<String, Page<T>> fetch;

    private PageIterable(Function<String, Page<T>> fetch) {
        this.fetch = fetch;
    }

    /**
     * Creates the walk. Resources create these and applications have no need to.
     *
     * @param <P> the page type
     * @param <T> the item type
     * @param fetch fetches a page; the argument is the cursor, null for the first page
     * @param items reads a page's items
     * @param nextCursor reads a page's next cursor
     * @return the walk
     */
    public static <P, T> PageIterable<T> of(Function<String, RaskResponse<P>> fetch, Function<P, List<T>> items,
            Function<P, String> nextCursor) {
        return new PageIterable<>(cursor -> {
            RaskResponse<P> response = fetch.apply(cursor);
            P body = response.data();
            List<T> found = body == null ? null : items.apply(body);
            return new Page<>(found == null ? Collections.emptyList() : found,
                    body == null ? null : nextCursor.apply(body), response.status(), response.headers());
        });
    }

    @Override
    public Iterator<T> iterator() {
        return new Walk();
    }

    /**
     * Streams the items lazily.
     *
     * @return a sequential stream
     */
    public Stream<T> stream() {
        return StreamSupport.stream(spliterator(), false);
    }

    private static final class Page<T> {
        final List<T> items;
        final String next;
        final int status;
        final HttpHeaders headers;

        Page(List<T> items, String next, int status, HttpHeaders headers) {
            this.items = items;
            this.next = next;
            this.status = status;
            this.headers = headers;
        }
    }

    private final class Walk implements Iterator<T> {
        private Iterator<T> page = Collections.emptyIterator();
        private String cursor;
        private boolean finished;
        // Every cursor the server has given on this walk, not only the last: a cycle such as A, B, A never repeats one
        // in a row.
        private final Set<String> given = new HashSet<>();
        private Page<T> repeating;

        @Override
        public boolean hasNext() {
            while (!page.hasNext() && !finished) {
                if (repeating != null) {
                    // Following a cursor a second time would get the same pages, for ever.
                    throw new RaskError("The list gave a cursor it had already given, so walking it would never end",
                            repeating.status, null, repeating.headers, null, null);
                }
                Page<T> fetched = fetch.apply(cursor);
                page = fetched.items.iterator();
                if (fetched.next == null) {
                    finished = true;
                } else if (!given.add(fetched.next)) {
                    repeating = fetched;
                }
                cursor = fetched.next;
            }
            return page.hasNext();
        }

        @Override
        public T next() {
            if (!hasNext()) {
                throw new NoSuchElementException();
            }
            return page.next();
        }
    }
}
