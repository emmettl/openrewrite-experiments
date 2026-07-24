package io.github.emmettl.rewrite.fixtures.common;

/**
 * Wraps an error reply in an unchecked exception, so a migrated handler can {@code throw} where it
 * used to emit an error.
 */
public class RequestException extends RuntimeException {

    private RequestException(Object errorReply) {
        super(String.valueOf(errorReply));
    }

    public static RequestException fromReply(Object errorReply) {
        return new RequestException(errorReply);
    }
}
