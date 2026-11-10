package ai.rask.sdk.model;

/**
 * A string enum the server may extend: values added after this SDK version was built stay readable and comparable.
 * Compare with {@code equals} or switch on {@link #value()}.
 */
public abstract class OpenEnum {
    private final String value;
    private final boolean known;

    /**
     * Creates a value.
     *
     * @param value the wire value
     * @param known whether this SDK version lists it
     */
    protected OpenEnum(String value, boolean known) {
        this.value = value;
        this.known = known;
    }

    /**
     * Returns the wire value.
     *
     * @return the string the server sent
     */
    public final String value() {
        return value;
    }

    /**
     * Tells whether this SDK version lists the value.
     *
     * @return false for a value added to the API later
     */
    public final boolean isKnown() {
        return known;
    }

    @Override
    public final boolean equals(Object other) {
        return other != null && other.getClass() == getClass() && value.equals(((OpenEnum) other).value);
    }

    @Override
    public final int hashCode() {
        return value.hashCode();
    }

    @Override
    public final String toString() {
        return value;
    }
}
