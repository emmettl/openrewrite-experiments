package io.github.emmettl.rewrite;

import org.junit.jupiter.api.Test;
import org.openrewrite.java.JavaParser;
import org.openrewrite.test.RecipeSpec;
import org.openrewrite.test.RewriteTest;

import static org.openrewrite.java.Assertions.java;

/**
 * Mirrors the compiled fixtures under {@code fixtures/test}. The class name is left alone — only the
 * body of the test changes.
 */
class HandlerTestToDirectCallTest implements RewriteTest {

    @Override
    public void defaults(RecipeSpec spec) {
        spec.recipe(new HandlerTestToDirectCall(
                        "io.github.emmettl.rewrite.fixtures.EventEmitter emit(..)",
                        "io.github.emmettl.rewrite.fixtures.common.MessageConstants.SEND_REPLY"))
                .parser(JavaParser.fromJavaVersion().classpath(JavaParser.runtimeClasspath()));
    }

    @Test
    void assertsOnTheReturnedResponse() {
        rewriteRun(
          java(
            """
              package io.github.emmettl.rewrite.fixtures.test;

              import io.github.emmettl.rewrite.fixtures.EventEmitter;
              import io.github.emmettl.rewrite.fixtures.common.MessageConstants;
              import io.github.emmettl.rewrite.fixtures.domain.MessageInfo;
              import io.github.emmettl.rewrite.fixtures.domain.MyRequestType;
              import io.github.emmettl.rewrite.fixtures.domain.MyResponseType;
              import io.github.emmettl.rewrite.fixtures.handler.MyRequestHandler;
              import org.junit.jupiter.api.Test;
              import org.mockito.ArgumentCaptor;
              import org.mockito.Captor;
              import org.mockito.Mock;

              import static org.assertj.core.api.Assertions.assertThat;
              import static org.mockito.ArgumentMatchers.any;
              import static org.mockito.ArgumentMatchers.eq;
              import static org.mockito.Mockito.verify;

              public class MyRequestHandlerTest {

                  @Mock
                  EventEmitter eventEmitter;
                  @Captor
                  private ArgumentCaptor<MyResponseType> responseCaptor;

                  @Test
                  public void handleRequestTest() {
                      MyRequestHandler myRequestHandler = new MyRequestHandler();
                      myRequestHandler.handleRequest(new MyRequestType(), new MessageInfo("foo"));

                      verify(eventEmitter).emit(eq(MessageConstants.SEND_REPLY), responseCaptor.capture(), any(MessageInfo.class));

                      var response = responseCaptor.getValue();

                      assertThat(response).isNotNull();
                  }
              }
              """,
            """
              package io.github.emmettl.rewrite.fixtures.test;

              import io.github.emmettl.rewrite.fixtures.EventEmitter;
              import io.github.emmettl.rewrite.fixtures.domain.MyRequestType;
              import io.github.emmettl.rewrite.fixtures.domain.MyResponseType;
              import io.github.emmettl.rewrite.fixtures.handler.MyRequestHandler;
              import org.junit.jupiter.api.Test;
              import org.mockito.Mock;

              import static org.assertj.core.api.Assertions.assertThat;

              public class MyRequestHandlerTest {

                  @Mock
                  EventEmitter eventEmitter;

                  @Test
                  public void handleRequestTest() {
                      MyRequestHandler myRequestHandler = new MyRequestHandler();
                      var response = myRequestHandler.handleRequest(new MyRequestType());

                      assertThat(response).isNotNull();
                  }
              }
              """
          )
        );
    }

    /**
     * The real-world shape: the routing argument is matched with {@code eq(messageInfo)} rather than
     * {@code any(MessageInfo.class)}, the captured value has an explicit type, and unrelated
     * statements (and a second, event verify) sit between the handler call and the reply verify. The
     * routing argument must still be dropped and the handler call found across the gap.
     */
    @Test
    void dropsRoutingArgMatchedByEqAcrossInterveningStatements() {
        rewriteRun(
          java(
            """
              package io.github.emmettl.rewrite.fixtures.test;

              import io.github.emmettl.rewrite.fixtures.EventEmitter;
              import io.github.emmettl.rewrite.fixtures.common.MessageConstants;
              import io.github.emmettl.rewrite.fixtures.domain.MessageInfo;
              import io.github.emmettl.rewrite.fixtures.domain.MyRequestType;
              import io.github.emmettl.rewrite.fixtures.domain.MyResponseType;
              import io.github.emmettl.rewrite.fixtures.handler.MyRequestHandler;
              import org.junit.jupiter.api.Test;
              import org.mockito.ArgumentCaptor;
              import org.mockito.Captor;
              import org.mockito.Mock;

              import static org.assertj.core.api.Assertions.assertThat;
              import static org.mockito.ArgumentMatchers.any;
              import static org.mockito.ArgumentMatchers.eq;
              import static org.mockito.Mockito.verify;

              public class MyRequestHandlerTest {

                  @Mock
                  EventEmitter eventEmitter;
                  @Captor
                  private ArgumentCaptor<MyResponseType> responseCaptor;

                  private final MessageInfo messageInfo = new MessageInfo("corr");

                  @Test
                  public void handleRequestTest() {
                      MyRequestHandler myRequestHandler = new MyRequestHandler();
                      myRequestHandler.handleRequest(new MyRequestType(), messageInfo);

                      assertThat(myRequestHandler).isNotNull();

                      verify(eventEmitter).emit(eq(MessageConstants.SEND_REPLY), responseCaptor.capture(), eq(messageInfo));
                      MyResponseType reply = responseCaptor.getValue();
                      assertThat(reply).isNotNull();
                      verify(eventEmitter).emit(eq("AnEvent"), any());
                  }
              }
              """,
            """
              package io.github.emmettl.rewrite.fixtures.test;

              import io.github.emmettl.rewrite.fixtures.EventEmitter;
              import io.github.emmettl.rewrite.fixtures.domain.MessageInfo;
              import io.github.emmettl.rewrite.fixtures.domain.MyRequestType;
              import io.github.emmettl.rewrite.fixtures.domain.MyResponseType;
              import io.github.emmettl.rewrite.fixtures.handler.MyRequestHandler;
              import org.junit.jupiter.api.Test;
              import org.mockito.Mock;

              import static org.assertj.core.api.Assertions.assertThat;
              import static org.mockito.ArgumentMatchers.any;
              import static org.mockito.ArgumentMatchers.eq;
              import static org.mockito.Mockito.verify;

              public class MyRequestHandlerTest {

                  @Mock
                  EventEmitter eventEmitter;

                  private final MessageInfo messageInfo = new MessageInfo("corr");

                  @Test
                  public void handleRequestTest() {
                      MyRequestHandler myRequestHandler = new MyRequestHandler();
                      var reply = myRequestHandler.handleRequest(new MyRequestType());

                      assertThat(myRequestHandler).isNotNull();
                      assertThat(reply).isNotNull();
                      verify(eventEmitter).emit(eq("AnEvent"), any());
                  }
              }
              """
          )
        );
    }

    /**
     * A test class may hold captors for other emits. Only the one whose reply round trip is
     * actually collapsed is removed; the rest are still in use and must survive.
     */
    @Test
    void removesOnlyTheResponseCaptor() {
        rewriteRun(
          java(
            """
              package io.github.emmettl.rewrite.fixtures.test;

              import io.github.emmettl.rewrite.fixtures.EventEmitter;
              import io.github.emmettl.rewrite.fixtures.common.MessageConstants;
              import io.github.emmettl.rewrite.fixtures.domain.MessageInfo;
              import io.github.emmettl.rewrite.fixtures.domain.MyRequestType;
              import io.github.emmettl.rewrite.fixtures.domain.MyResponseType;
              import io.github.emmettl.rewrite.fixtures.domain.SomeEventOrOther;
              import io.github.emmettl.rewrite.fixtures.handler.MyRequestHandler;
              import org.junit.jupiter.api.Test;
              import org.mockito.ArgumentCaptor;
              import org.mockito.Captor;
              import org.mockito.Mock;

              import static org.assertj.core.api.Assertions.assertThat;
              import static org.mockito.ArgumentMatchers.any;
              import static org.mockito.ArgumentMatchers.eq;
              import static org.mockito.Mockito.verify;

              public class MyRequestHandlerTest {

                  @Mock
                  EventEmitter eventEmitter;
                  @Captor
                  private ArgumentCaptor<MyResponseType> responseCaptor;
                  @Captor
                  private ArgumentCaptor<SomeEventOrOther> eventCaptor;

                  @Test
                  public void handleRequestTest() {
                      MyRequestHandler myRequestHandler = new MyRequestHandler();
                      myRequestHandler.handleRequest(new MyRequestType(), new MessageInfo("foo"));

                      verify(eventEmitter).emit(eq(MessageConstants.SEND_REPLY), responseCaptor.capture(), any(MessageInfo.class));

                      var response = responseCaptor.getValue();

                      assertThat(response).isNotNull();
                  }

                  @Test
                  public void emitsEventTest() {
                      verify(eventEmitter).emit(eq("AnEvent"), eventCaptor.capture());

                      assertThat(eventCaptor.getValue()).isNotNull();
                  }
              }
              """,
            """
              package io.github.emmettl.rewrite.fixtures.test;

              import io.github.emmettl.rewrite.fixtures.EventEmitter;
              import io.github.emmettl.rewrite.fixtures.domain.MyRequestType;
              import io.github.emmettl.rewrite.fixtures.domain.MyResponseType;
              import io.github.emmettl.rewrite.fixtures.domain.SomeEventOrOther;
              import io.github.emmettl.rewrite.fixtures.handler.MyRequestHandler;
              import org.junit.jupiter.api.Test;
              import org.mockito.ArgumentCaptor;
              import org.mockito.Captor;
              import org.mockito.Mock;

              import static org.assertj.core.api.Assertions.assertThat;
              import static org.mockito.ArgumentMatchers.eq;
              import static org.mockito.Mockito.verify;

              public class MyRequestHandlerTest {

                  @Mock
                  EventEmitter eventEmitter;
                  @Captor
                  private ArgumentCaptor<SomeEventOrOther> eventCaptor;

                  @Test
                  public void handleRequestTest() {
                      MyRequestHandler myRequestHandler = new MyRequestHandler();
                      var response = myRequestHandler.handleRequest(new MyRequestType());

                      assertThat(response).isNotNull();
                  }

                  @Test
                  public void emitsEventTest() {
                      verify(eventEmitter).emit(eq("AnEvent"), eventCaptor.capture());

                      assertThat(eventCaptor.getValue()).isNotNull();
                  }
              }
              """
          )
        );
    }

    /**
     * A verification of some other emit is not a reply round trip, so nothing is collapsed and the
     * captor stays.
     */
    @Test
    void leavesVerificationsOfOtherEmitsAlone() {
        rewriteRun(
          java(
            """
              package io.github.emmettl.rewrite.fixtures.test;

              import io.github.emmettl.rewrite.fixtures.EventEmitter;
              import io.github.emmettl.rewrite.fixtures.domain.SomeEventOrOther;
              import org.junit.jupiter.api.Test;
              import org.mockito.ArgumentCaptor;
              import org.mockito.Captor;
              import org.mockito.Mock;

              import static org.assertj.core.api.Assertions.assertThat;
              import static org.mockito.ArgumentMatchers.eq;
              import static org.mockito.Mockito.verify;

              public class EventPublishingTest {

                  @Mock
                  EventEmitter eventEmitter;
                  @Captor
                  private ArgumentCaptor<SomeEventOrOther> eventCaptor;

                  @Test
                  public void publishesEvent() {
                      verify(eventEmitter).emit(eq("AnEvent"), eventCaptor.capture());

                      var event = eventCaptor.getValue();

                      assertThat(event).isNotNull();
                  }
              }
              """
          )
        );
    }
}
