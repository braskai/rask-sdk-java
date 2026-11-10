package ai.rask.sdk.internal;

import java.util.regex.Pattern;

/** Masks secrets in anything that may be printed. Internal API. */
public final class Redaction {
    private static final Pattern PRESIGNED = Pattern.compile(
            "(?i)((?:X-Amz-(?:Signature|Credential|Security-Token)|X-Goog-Signature|Signature|sig)=)[^&\\s\"']+");
    private static final Pattern BEARER = Pattern.compile("(?i)(Bearer\\s+)[^\\s\"']+");

    private Redaction() {
    }

    /**
     * Masks presigned signatures and bearer tokens in free text.
     *
     * @param text the text
     * @return the text with secret values replaced by {@code ***}
     */
    public static String text(String text) {
        return BEARER.matcher(PRESIGNED.matcher(text).replaceAll("$1***")).replaceAll("$1***");
    }

    /**
     * Masks a URL for a debug log.
     *
     * @param url the URL
     * @param presigned true when its query string is a signature, which then goes entirely
     * @return the URL to print
     */
    public static String url(String url, boolean presigned) {
        int query = url.indexOf('?');
        if (presigned && query >= 0) {
            return url.substring(0, query) + "?***";
        }
        return text(url);
    }
}
