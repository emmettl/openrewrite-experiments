package io.github.emmettl.rewrite.fixtures.handler;

import io.github.emmettl.rewrite.fixtures.DetailsClient;
import io.github.emmettl.rewrite.fixtures.EventEmitter;
import io.github.emmettl.rewrite.fixtures.annotation.EventListener;
import io.github.emmettl.rewrite.fixtures.common.MessageConstants;
import io.github.emmettl.rewrite.fixtures.domain.MessageInfo;
import io.github.emmettl.rewrite.fixtures.domain.MyAsyncReply;
import io.github.emmettl.rewrite.fixtures.domain.MyRequestType;
import io.github.emmettl.rewrite.fixtures.domain.SomeErrorType;

/** The asynchronous listener shape: the reply is emitted from inside a completion stage. */
public class AsyncRequestHandler {

    private EventEmitter eventEmitter;
    private DetailsClient detailsClient;

    @EventListener(MyRequestType.TYPE)
    public void handleRequest(MyRequestType request, MessageInfo messageInfo) {
        detailsClient.fetchDetails("id")
                .thenAccept(details -> {
                    eventEmitter.emit(MessageConstants.SEND_REPLY, new MyAsyncReply(details), messageInfo);
                })
                .exceptionally(e -> {
                    eventEmitter.emit(MessageConstants.SEND_ERROR, new SomeErrorType("bad"), messageInfo);
                    return null;
                });
    }
}
