package io.github.emmettl.rewrite;

import org.junit.jupiter.api.Test;
import org.openrewrite.java.JavaParser;
import org.openrewrite.test.RecipeSpec;
import org.openrewrite.test.RewriteTest;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;
import static org.openrewrite.java.Assertions.java;

/**
 * The migration declines silently, which makes a run look complete when it is not. This recipe is
 * how you find out what it left: a marker in the diff and a row in the data table, per handler.
 */
class FindSkippedHandlersTest implements RewriteTest {

    @Override
    public void defaults(RecipeSpec spec) {
        spec.recipe(new FindSkippedHandlers(
                        "io.github.emmettl.rewrite.fixtures.annotation.EventListener",
                        "io.github.emmettl.rewrite.fixtures.annotation.RequestHandler",
                        "io.github.emmettl.rewrite.fixtures.EventEmitter emit(..)",
                        "io.github.emmettl.rewrite.fixtures.common.MessageConstants.SEND_REPLY",
                        "io.github.emmettl.rewrite.fixtures.common.MessageConstants.SEND_ERROR",
                        "io.github.emmettl.rewrite.fixtures.common.RequestException.fromReply"))
                .parser(JavaParser.fromJavaVersion().classpath(JavaParser.runtimeClasspath()));
    }

    /**
     * Three listeners in one class: one with nothing to return, one whose surviving emit still needs
     * the routing argument, and one that migrates cleanly. Only the first two are reported — the
     * third is what proves this is not just a list of every listener.
     */
    @Test
    void marksWhatTheMigrationLeavesBehind() {
        rewriteRun(
          spec -> spec.dataTable(SkippedHandlers.Row.class, rows ->
                  assertThat(rows)
                          .extracting(SkippedHandlers.Row::getClassName,
                                      SkippedHandlers.Row::getHandler,
                                      SkippedHandlers.Row::getReason)
                          .containsExactly(
                                  tuple("MixedHandlers", "handleAnotherEvent",
                                          FindSkippedHandlers.NO_REPLY_EMIT),
                                  tuple("MixedHandlers", "handleRequest",
                                          FindSkippedHandlers.ROUTING_STILL_USED))),
          java(
            """
              package io.github.emmettl.rewrite.fixtures.handler;

              import io.github.emmettl.rewrite.fixtures.EventEmitter;
              import io.github.emmettl.rewrite.fixtures.annotation.EventListener;
              import io.github.emmettl.rewrite.fixtures.common.MessageConstants;
              import io.github.emmettl.rewrite.fixtures.domain.MessageInfo;
              import io.github.emmettl.rewrite.fixtures.domain.MyRequestType;
              import io.github.emmettl.rewrite.fixtures.domain.MyResponseType;
              import io.github.emmettl.rewrite.fixtures.domain.SomeEventOrOther;

              public class MixedHandlers {

                  private EventEmitter eventEmitter;

                  @EventListener("AnotherEvent")
                  public void handleAnotherEvent(String someEvent, String someOther) {
                      eventEmitter.emit("AnEvent", new SomeEventOrOther(someEvent, someOther));
                  }

                  @EventListener(MyRequestType.TYPE)
                  public void handleRequest(MyRequestType requestType, MessageInfo messageInfo) {
                      eventEmitter.emit("AnEvent", new SomeEventOrOther("a", "b"), messageInfo);
                      eventEmitter.emit(MessageConstants.SEND_REPLY, new MyResponseType(), messageInfo);
                  }

                  @EventListener(MyRequestType.TYPE)
                  public void handleMigratable(MyRequestType requestType, MessageInfo messageInfo) {
                      eventEmitter.emit(MessageConstants.SEND_REPLY, new MyResponseType(), messageInfo);
                  }
              }
              """,
            """
              package io.github.emmettl.rewrite.fixtures.handler;

              import io.github.emmettl.rewrite.fixtures.EventEmitter;
              import io.github.emmettl.rewrite.fixtures.annotation.EventListener;
              import io.github.emmettl.rewrite.fixtures.common.MessageConstants;
              import io.github.emmettl.rewrite.fixtures.domain.MessageInfo;
              import io.github.emmettl.rewrite.fixtures.domain.MyRequestType;
              import io.github.emmettl.rewrite.fixtures.domain.MyResponseType;
              import io.github.emmettl.rewrite.fixtures.domain.SomeEventOrOther;

              public class MixedHandlers {

                  private EventEmitter eventEmitter;

                  /*~~(No reply emit: a fire-and-forget listener, with no response to return)~~>*/@EventListener("AnotherEvent")
                  public void handleAnotherEvent(String someEvent, String someOther) {
                      eventEmitter.emit("AnEvent", new SomeEventOrOther(someEvent, someOther));
                  }

                  /*~~(The routing argument is still read by an emit the migration leaves alone)~~>*/@EventListener(MyRequestType.TYPE)
                  public void handleRequest(MyRequestType requestType, MessageInfo messageInfo) {
                      eventEmitter.emit("AnEvent", new SomeEventOrOther("a", "b"), messageInfo);
                      eventEmitter.emit(MessageConstants.SEND_REPLY, new MyResponseType(), messageInfo);
                  }

                  @EventListener(MyRequestType.TYPE)
                  public void handleMigratable(MyRequestType requestType, MessageInfo messageInfo) {
                      eventEmitter.emit(MessageConstants.SEND_REPLY, new MyResponseType(), messageInfo);
                  }
              }
              """
          )
        );
    }

    /**
     * The asynchronous decline: a reply emitted from a lambda the migration cannot follow — here a
     * {@code forEach} rather than a stage mapping.
     */
    @Test
    void namesTheAsyncShapeItCannotFollow() {
        rewriteRun(
          spec -> spec.dataTable(SkippedHandlers.Row.class, rows ->
                  assertThat(rows)
                          .extracting(SkippedHandlers.Row::getHandler, SkippedHandlers.Row::getReason)
                          .containsExactly(tuple("handleRequest", FindSkippedHandlers.UNRECOGNISED_ASYNC))),
          java(
            """
              package io.github.emmettl.rewrite.fixtures.handler;

              import io.github.emmettl.rewrite.fixtures.EventEmitter;
              import io.github.emmettl.rewrite.fixtures.annotation.EventListener;
              import io.github.emmettl.rewrite.fixtures.common.MessageConstants;
              import io.github.emmettl.rewrite.fixtures.domain.MessageInfo;
              import io.github.emmettl.rewrite.fixtures.domain.MyRequestType;
              import io.github.emmettl.rewrite.fixtures.domain.MyResponseType;

              import java.util.List;

              public class ForEachHandler {

                  private EventEmitter eventEmitter;

                  @EventListener(MyRequestType.TYPE)
                  public void handleRequest(MyRequestType requestType, MessageInfo messageInfo) {
                      List.of("a").forEach(each -> {
                          eventEmitter.emit(MessageConstants.SEND_REPLY, new MyResponseType(), messageInfo);
                      });
                  }
              }
              """,
            """
              package io.github.emmettl.rewrite.fixtures.handler;

              import io.github.emmettl.rewrite.fixtures.EventEmitter;
              import io.github.emmettl.rewrite.fixtures.annotation.EventListener;
              import io.github.emmettl.rewrite.fixtures.common.MessageConstants;
              import io.github.emmettl.rewrite.fixtures.domain.MessageInfo;
              import io.github.emmettl.rewrite.fixtures.domain.MyRequestType;
              import io.github.emmettl.rewrite.fixtures.domain.MyResponseType;

              import java.util.List;

              public class ForEachHandler {

                  private EventEmitter eventEmitter;

                  /*~~(The reply is emitted from a lambda this recipe cannot follow: only a thenAccept on a completion stage, as the last statement of its block)~~>*/@EventListener(MyRequestType.TYPE)
                  public void handleRequest(MyRequestType requestType, MessageInfo messageInfo) {
                      List.of("a").forEach(each -> {
                          eventEmitter.emit(MessageConstants.SEND_REPLY, new MyResponseType(), messageInfo);
                      });
                  }
              }
              """
          )
        );
    }

    /**
     * A class the migration handles completely has nothing to report. Asserting the source is
     * untouched is the whole assertion: a row and a marker are written together, so no marker means
     * no row — and there is no empty table to inspect, since a table that takes no rows is never
     * created.
     */
    @Test
    void reportsNothingWhenEverythingMigrates() {
        rewriteRun(
          java(
            """
              package io.github.emmettl.rewrite.fixtures.handler;

              import io.github.emmettl.rewrite.fixtures.EventEmitter;
              import io.github.emmettl.rewrite.fixtures.annotation.EventListener;
              import io.github.emmettl.rewrite.fixtures.common.MessageConstants;
              import io.github.emmettl.rewrite.fixtures.domain.MessageInfo;
              import io.github.emmettl.rewrite.fixtures.domain.MyRequestType;
              import io.github.emmettl.rewrite.fixtures.domain.MyResponseType;

              public class MyRequestHandler {

                  private EventEmitter eventEmitter;

                  @EventListener(MyRequestType.TYPE)
                  public void handleRequest(MyRequestType requestType, MessageInfo messageInfo) {
                      eventEmitter.emit(MessageConstants.SEND_REPLY, new MyResponseType(), messageInfo);
                  }
              }
              """
          )
        );
    }
}
