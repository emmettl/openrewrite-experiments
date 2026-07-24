package io.github.emmettl.rewrite.fixtures.common;

/**
 * Wraps an error reply in an unchecked exception, so a migrated handler can {@code throw} where it
 * used to emit an error. The reply is retained and exposed so a test can assert on it after catching
 * the exception.
 */
public class RequestException extends RuntimeException {

    private final Object reply;

    private RequestException(Object errorReply) {
        super(String.valueOf(errorReply));
        this.reply = errorReply;
    }

    public static RequestException fromReply(Object errorReply) {
        return new RequestException(errorReply);
    }

    public Object getReply() {
        return reply;
    }
}
