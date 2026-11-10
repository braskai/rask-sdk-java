package ai.rask.sdk;

import java.util.Collections;
import java.util.Iterator;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.function.Function;
import java.util.stream.Stream;
import java.util.stream.StreamSupport;

/**
 * Walks every item of a list, fetching the next page over {@code next_cursor} only when the items so far are used up.
 * Each {@link #iterator()} starts again from the first page. A short or empty page does not end the walk; only a null
 * {@code next_cursor} does.
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
            P body = fetch.apply(cursor).data();
            if (body == null) {
                return new Page<>(Collections.emptyList(), null);
            }
            List<T> found = items.apply(body);
            return new Page<>(found == null ? Collections.emptyList() : found, nextCursor.apply(body));
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

        Page(List<T> items, String next) {
            this.items = items;
            this.next = next;
        }
    }

    private final class Walk implements Iterator<T> {
        private Iterator<T> page = Collections.emptyIterator();
        private String cursor;
        private boolean finished;

        @Override
        public boolean hasNext() {
            while (!page.hasNext() && !finished) {
                Page<T> fetched = fetch.apply(cursor);
                page = fetched.items.iterator();
                // A server that repeats a cursor would loop forever; stop instead.
                if (fetched.next == null || fetched.next.equals(cursor)) {
                    finished = true;
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
