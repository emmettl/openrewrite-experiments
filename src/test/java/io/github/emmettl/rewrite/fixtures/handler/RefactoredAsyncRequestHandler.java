package io.github.emmettl.rewrite.fixtures.handler;

import io.github.emmettl.rewrite.fixtures.EventEmitter;
import io.github.emmettl.rewrite.fixtures.SomeAsyncClient;
import io.github.emmettl.rewrite.fixtures.annotation.RequestHandler;
import io.github.emmettl.rewrite.fixtures.common.RequestException;
import io.github.emmettl.rewrite.fixtures.domain.MyAsyncResponseType;
import io.github.emmettl.rewrite.fixtures.domain.MyRequestType;
import io.github.emmettl.rewrite.fixtures.domain.SomeErrorType;

import java.util.concurrent.CompletableFuture;

/** What {@link AsyncRequestHandler} becomes: the stage itself is the reply. */
public class RefactoredAsyncRequestHandler {

    private EventEmitter eventEmitter;
    private SomeAsyncClient someAsyncClient;

    @RequestHandler
    public CompletableFuture<MyAsyncResponseType> handleRequest(MyRequestType request) {
        return someAsyncClient.fetchSomething("someId")
                .thenApply(MyAsyncResponseType::new)
                .exceptionally(e -> {
                    throw RequestException.fromReply(new SomeErrorType("bad"));
                });
    }
}
