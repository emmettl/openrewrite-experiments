package io.github.emmettl.rewrite.fixtures.domain;

/** A reply built from one value, so the mapping lambda can collapse to {@code MyTradeReply::new}. */
public record MyTradeReply(TradeDetails details) {
}
