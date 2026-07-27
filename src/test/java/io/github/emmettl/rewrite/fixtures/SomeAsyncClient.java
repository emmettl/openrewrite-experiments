package io.github.emmettl.rewrite.fixtures;

import io.github.emmettl.rewrite.fixtures.domain.SomeFetchedThing;

import java.util.concurrent.CompletableFuture;

/** A client that answers asynchronously, so a handler using it replies from inside a stage. */
public interface SomeAsyncClient {

    CompletableFuture<SomeFetchedThing> fetchSomething(String someId);
}
