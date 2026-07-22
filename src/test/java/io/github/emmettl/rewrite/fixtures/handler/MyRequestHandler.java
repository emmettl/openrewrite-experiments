package io.github.emmettl.rewrite.fixtures.handler;

import io.github.emmettl.rewrite.fixtures.EventEmitter;
import io.github.emmettl.rewrite.fixtures.annotation.EventListener;
import io.github.emmettl.rewrite.fixtures.common.MessageConstants;
import io.github.emmettl.rewrite.fixtures.domain.MessageInfo;
import io.github.emmettl.rewrite.fixtures.domain.MyRequestType;
import io.github.emmettl.rewrite.fixtures.domain.MyResponseType;
import io.github.emmettl.rewrite.fixtures.domain.SomeEventOrOther;

public class MyRequestHandler {

    private EventEmitter eventEmitter;

    @EventListener(MyRequestType.TYPE)
    public void handleRequest(MyRequestType requestType, MessageInfo messageInfo) {
        try {
            // do some work
            eventEmitter.emit("AnEvent", new SomeEventOrOther("someEvent", "someOther"));
            eventEmitter.emit(MessageConstants.SEND_REPLY, new MyResponseType(), messageInfo);
        } catch (Exception e) {
            eventEmitter.emit(MessageConstants.SEND_ERROR, e);
        }
    }
}
