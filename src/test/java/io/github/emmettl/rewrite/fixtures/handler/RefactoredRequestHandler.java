package io.github.emmettl.rewrite.fixtures.handler;

import io.github.emmettl.rewrite.fixtures.EventEmitter;
import io.github.emmettl.rewrite.fixtures.annotation.RequestHandler;
import io.github.emmettl.rewrite.fixtures.common.RequestException;
import io.github.emmettl.rewrite.fixtures.domain.MyRequestType;
import io.github.emmettl.rewrite.fixtures.domain.MyResponseType;
import io.github.emmettl.rewrite.fixtures.domain.SomeEventOrOther;

public class RefactoredRequestHandler {

    private EventEmitter eventEmitter;

    @RequestHandler
    public MyResponseType handleRequest(MyRequestType requestType) {
        try {
            // do some work
            eventEmitter.emit("AnEvent", new SomeEventOrOther("someEvent", "someOther"));
            return new MyResponseType();
        } catch (Exception e) {
            throw RequestException.fromReply(e);
        }
    }
}
