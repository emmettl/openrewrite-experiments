package io.github.emmettl.rewrite;

import org.junit.jupiter.api.Test;
import org.openrewrite.java.JavaParser;
import org.openrewrite.test.RecipeSpec;
import org.openrewrite.test.RewriteTest;

import static org.openrewrite.java.Assertions.java;

/**
 * The sources here mirror the compiled fixtures under {@code fixtures/handler}. Those exist so that
 * both sides of the migration are type-checked by the IDE and the compiler; these literals are what
 * the recipe actually runs against, with the fixture types supplied on the parser classpath so the
 * LST is fully type-attributed.
 */
class EventListenerToRequestHandlerTest implements RewriteTest {

    @Override
    public void defaults(RecipeSpec spec) {
        spec.recipe(new EventListenerToRequestHandler(
                        "io.github.emmettl.rewrite.fixtures.annotation.EventListener",
                        "io.github.emmettl.rewrite.fixtures.annotation.RequestHandler",
                        "io.github.emmettl.rewrite.fixtures.EventEmitter emit(..)",
                        "io.github.emmettl.rewrite.fixtures.common.MessageConstants.SEND_REPLY",
                        "io.github.emmettl.rewrite.fixtures.common.MessageConstants.SEND_ERROR"))
                .parser(JavaParser.fromJavaVersion().classpath(JavaParser.runtimeClasspath()));
    }

    @Test
    void migratesHandlerToRequestResponse() {
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
              """,
            """
              package io.github.emmettl.rewrite.fixtures.handler;

              import io.github.emmettl.rewrite.fixtures.EventEmitter;
              import io.github.emmettl.rewrite.fixtures.annotation.RequestHandler;
              import io.github.emmettl.rewrite.fixtures.domain.MyRequestType;
              import io.github.emmettl.rewrite.fixtures.domain.MyResponseType;
              import io.github.emmettl.rewrite.fixtures.domain.SomeEventOrOther;

              public class MyRequestHandler {

                  private EventEmitter eventEmitter;

                  @RequestHandler
                  public MyResponseType handleRequest(MyRequestType requestType) {
                      try {
                          // do some work
                          eventEmitter.emit("AnEvent", new SomeEventOrOther("someEvent", "someOther"));
                          return new MyResponseType();
                      } catch (Exception e) {
                          throw new RuntimeException(e);
                      }
                  }
              }
              """
          )
        );
    }

    /**
     * A listener that never emits a reply has no return value to recover, so migrating it would
     * produce something that does not compile. It is left alone.
     */
    @Test
    void leavesListenersWithoutAReplyAlone() {
        rewriteRun(
          java(
            """
              package io.github.emmettl.rewrite.fixtures.handler;

              import io.github.emmettl.rewrite.fixtures.EventEmitter;
              import io.github.emmettl.rewrite.fixtures.annotation.EventListener;
              import io.github.emmettl.rewrite.fixtures.domain.MyRequestType;
              import io.github.emmettl.rewrite.fixtures.domain.SomeEventOrOther;

              public class FireAndForgetHandler {

                  private EventEmitter eventEmitter;

                  @EventListener(MyRequestType.TYPE)
                  public void handleRequest(MyRequestType requestType) {
                      eventEmitter.emit("AnEvent", new SomeEventOrOther("someEvent", "someOther"));
                  }
              }
              """
          )
        );
    }

    /**
     * Listeners of other shapes — different arguments, no request/reply round trip — are a
     * different pattern and must be left exactly as they are, even when they sit in the same class
     * as one that does migrate.
     */
    @Test
    void leavesOtherListenerShapesAloneAlongsideAMigratedOne() {
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
              import io.github.emmettl.rewrite.fixtures.domain.SomeEventOrOther;

              public class MixedHandlers {

                  private EventEmitter eventEmitter;

                  @EventListener("NEW_TRADE")
                  public void handleNewTrade(String tradeId, String account) {
                      eventEmitter.emit("AnEvent", new SomeEventOrOther(tradeId, account));
                  }

                  @EventListener(MyRequestType.TYPE)
                  public void handleRequest(MyRequestType requestType, MessageInfo messageInfo) {
                      eventEmitter.emit(MessageConstants.SEND_REPLY, new MyResponseType(), messageInfo);
                  }
              }
              """,
            """
              package io.github.emmettl.rewrite.fixtures.handler;

              import io.github.emmettl.rewrite.fixtures.EventEmitter;
              import io.github.emmettl.rewrite.fixtures.annotation.EventListener;
              import io.github.emmettl.rewrite.fixtures.annotation.RequestHandler;
              import io.github.emmettl.rewrite.fixtures.domain.MyRequestType;
              import io.github.emmettl.rewrite.fixtures.domain.MyResponseType;
              import io.github.emmettl.rewrite.fixtures.domain.SomeEventOrOther;

              public class MixedHandlers {

                  private EventEmitter eventEmitter;

                  @EventListener("NEW_TRADE")
                  public void handleNewTrade(String tradeId, String account) {
                      eventEmitter.emit("AnEvent", new SomeEventOrOther(tradeId, account));
                  }

                  @RequestHandler
                  public MyResponseType handleRequest(MyRequestType requestType) {
                      return new MyResponseType();
                  }
              }
              """
          )
        );
    }

    /**
     * Emitting is not by itself a reason to rewrite — only methods carrying the listener annotation
     * are migrated.
     */
    @Test
    void leavesUnannotatedMethodsAlone() {
        rewriteRun(
          java(
            """
              package io.github.emmettl.rewrite.fixtures.handler;

              import io.github.emmettl.rewrite.fixtures.EventEmitter;
              import io.github.emmettl.rewrite.fixtures.common.MessageConstants;
              import io.github.emmettl.rewrite.fixtures.domain.MessageInfo;
              import io.github.emmettl.rewrite.fixtures.domain.MyResponseType;

              public class NotAHandler {

                  private EventEmitter eventEmitter;

                  public void reply(MessageInfo messageInfo) {
                      eventEmitter.emit(MessageConstants.SEND_REPLY, new MyResponseType(), messageInfo);
                  }
              }
              """
          )
        );
    }
}
