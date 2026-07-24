package io.github.emmettl.rewrite;

import org.junit.jupiter.api.Test;
import org.openrewrite.java.JavaParser;
import org.openrewrite.test.RecipeSpec;
import org.openrewrite.test.RewriteTest;

import static org.openrewrite.java.Assertions.java;

/**
 * The error-case companion. The handler now throws {@code RequestException.fromReply(error)}, so the
 * captured-error assertions become an {@code assertThatThrownBy(...).isInstanceOfSatisfying(...)}
 * block that unwraps the reply.
 */
class HandlerErrorTestToThrowsTest implements RewriteTest {

    @Override
    public void defaults(RecipeSpec spec) {
        spec.recipe(new HandlerErrorTestToThrows(
                        "io.github.emmettl.rewrite.fixtures.EventEmitter emit(..)",
                        "io.github.emmettl.rewrite.fixtures.common.MessageConstants.SEND_ERROR",
                        "io.github.emmettl.rewrite.fixtures.common.RequestException",
                        "getReply"))
                .parser(JavaParser.fromJavaVersion().classpath(JavaParser.runtimeClasspath()));
    }

    @Test
    void assertsTheHandlerThrows() {
        rewriteRun(
          java(
            """
              package io.github.emmettl.rewrite.fixtures.test;

              import io.github.emmettl.rewrite.fixtures.EventEmitter;
              import io.github.emmettl.rewrite.fixtures.common.MessageConstants;
              import io.github.emmettl.rewrite.fixtures.domain.MessageInfo;
              import io.github.emmettl.rewrite.fixtures.domain.MyRequestType;
              import io.github.emmettl.rewrite.fixtures.domain.SomeErrorType;
              import io.github.emmettl.rewrite.fixtures.handler.MyRequestHandler;
              import org.junit.jupiter.api.Test;
              import org.mockito.ArgumentCaptor;
              import org.mockito.Captor;
              import org.mockito.Mock;

              import static org.assertj.core.api.Assertions.assertThat;
              import static org.mockito.ArgumentMatchers.eq;
              import static org.mockito.Mockito.reset;
              import static org.mockito.Mockito.verify;

              public class MyRequestHandlerTest {

                  @Mock
                  EventEmitter eventEmitter;
                  @Captor
                  private ArgumentCaptor<SomeErrorType> errorCaptor;

                  private final MessageInfo messageInfo = new MessageInfo("corr");

                  @Test
                  public void handleRequestWithError() {
                      MyRequestHandler myRequestHandler = new MyRequestHandler();
                      myRequestHandler.handleRequest(new MyRequestType(), messageInfo);

                      verify(eventEmitter).emit(eq(MessageConstants.SEND_ERROR), errorCaptor.capture(), eq(messageInfo));
                      reset(eventEmitter);
                      SomeErrorType error = errorCaptor.getValue();
                      assertThat(error).isNotNull();
                      assertThat(error.ohNo()).isEqualTo("bad");
                  }
              }
              """,
            """
              package io.github.emmettl.rewrite.fixtures.test;

              import io.github.emmettl.rewrite.fixtures.EventEmitter;
              import io.github.emmettl.rewrite.fixtures.common.RequestException;
              import io.github.emmettl.rewrite.fixtures.domain.MessageInfo;
              import io.github.emmettl.rewrite.fixtures.domain.MyRequestType;
              import io.github.emmettl.rewrite.fixtures.domain.SomeErrorType;
              import io.github.emmettl.rewrite.fixtures.handler.MyRequestHandler;
              import org.junit.jupiter.api.Test;
              import org.mockito.ArgumentCaptor;
              import org.mockito.Captor;
              import org.mockito.Mock;

              import static org.assertj.core.api.Assertions.assertThat;
              import static org.assertj.core.api.Assertions.assertThatThrownBy;

              public class MyRequestHandlerTest {

                  @Mock
                  EventEmitter eventEmitter;
                  @Captor
                  private ArgumentCaptor<SomeErrorType> errorCaptor;

                  private final MessageInfo messageInfo = new MessageInfo("corr");

                  @Test
                  public void handleRequestWithError() {
                      MyRequestHandler myRequestHandler = new MyRequestHandler();
                      assertThatThrownBy(() -> myRequestHandler.handleRequest(new MyRequestType()))
                              .isInstanceOfSatisfying(RequestException.class, ex -> {
                                  SomeErrorType error = (SomeErrorType) ex.getReply();
                                  assertThat(error).isNotNull();
                                  assertThat(error.ohNo()).isEqualTo("bad");
                              });
                  }
              }
              """
          )
        );
    }
}
