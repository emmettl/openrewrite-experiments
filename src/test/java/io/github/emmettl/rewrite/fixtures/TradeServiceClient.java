package io.github.emmettl.rewrite.fixtures;

import io.github.emmettl.rewrite.fixtures.domain.TradeDetails;

import java.util.concurrent.CompletableFuture;

/** A client that answers asynchronously, so a handler using it replies from inside a stage. */
public interface TradeServiceClient {

    CompletableFuture<TradeDetails> fetchTradeDetails(String valor);
}
