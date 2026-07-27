package io.github.emmettl.rewrite.fixtures.handler;

import io.github.emmettl.rewrite.fixtures.EventEmitter;
import io.github.emmettl.rewrite.fixtures.TradeServiceClient;
import io.github.emmettl.rewrite.fixtures.annotation.EventListener;
import io.github.emmettl.rewrite.fixtures.common.MessageConstants;
import io.github.emmettl.rewrite.fixtures.domain.MessageInfo;
import io.github.emmettl.rewrite.fixtures.domain.MyRequestType;
import io.github.emmettl.rewrite.fixtures.domain.MyTradeReply;
import io.github.emmettl.rewrite.fixtures.domain.SomeErrorType;

/** The asynchronous listener shape: the reply is emitted from inside a completion stage. */
public class AsyncRequestHandler {

    private EventEmitter eventEmitter;
    private TradeServiceClient tradeServiceClient;

    @EventListener(MyRequestType.TYPE)
    public void handleLoadTrade(MyRequestType request, MessageInfo messageInfo) {
        tradeServiceClient.fetchTradeDetails("valor")
                .thenAccept(details -> {
                    eventEmitter.emit(MessageConstants.SEND_REPLY, new MyTradeReply(details), messageInfo);
                })
                .exceptionally(e -> {
                    eventEmitter.emit(MessageConstants.SEND_ERROR, new SomeErrorType("bad"), messageInfo);
                    return null;
                });
    }
}
