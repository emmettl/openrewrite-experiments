package io.github.emmettl.rewrite.fixtures.domain;

/** A reply built from one value, so the mapping lambda can collapse to {@code MyAsyncResponseType::new}. */
public record MyAsyncResponseType(SomeFetchedThing thing) {
}
