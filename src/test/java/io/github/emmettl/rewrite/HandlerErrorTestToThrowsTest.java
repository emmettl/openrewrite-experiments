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
              import io.github.emmettl.rewrite.fixtures.domain.MyRequestType;
              import io.github.emmettl.rewrite.fixtures.domain.SomeErrorType;
              import io.github.emmettl.rewrite.fixtures.handler.MyRequestHandler;
              import org.junit.jupiter.api.Test;
              import org.mockito.Mock;

              import static org.assertj.core.api.Assertions.assertThat;
              import static org.assertj.core.api.Assertions.assertThatThrownBy;

              public class MyRequestHandlerTest {

                  @Mock
                  EventEmitter eventEmitter;

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

    /**
     * A test class may hold captors for other emits. Only the one whose error round trip is actually
     * collapsed goes; the rest are still in use, and so are the Mockito imports they need.
     */
    @Test
    void removesOnlyTheErrorCaptor() {
        rewriteRun(
          java(
            """
              package io.github.emmettl.rewrite.fixtures.test;

              import io.github.emmettl.rewrite.fixtures.EventEmitter;
              import io.github.emmettl.rewrite.fixtures.common.MessageConstants;
              import io.github.emmettl.rewrite.fixtures.domain.MessageInfo;
              import io.github.emmettl.rewrite.fixtures.domain.MyRequestType;
              import io.github.emmettl.rewrite.fixtures.domain.SomeErrorType;
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
                  private ArgumentCaptor<SomeErrorType> errorCaptor;
                  @Captor
                  private ArgumentCaptor<SomeEventOrOther> eventCaptor;

                  private final MessageInfo messageInfo = new MessageInfo("corr");

                  @Test
                  public void handleRequestWithError() {
                      MyRequestHandler myRequestHandler = new MyRequestHandler();
                      myRequestHandler.handleRequest(new MyRequestType(), messageInfo);

                      verify(eventEmitter).emit(eq(MessageConstants.SEND_ERROR), errorCaptor.capture(), eq(messageInfo));
                      SomeErrorType error = errorCaptor.getValue();
                      assertThat(error).isNotNull();
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
              import io.github.emmettl.rewrite.fixtures.common.RequestException;
              import io.github.emmettl.rewrite.fixtures.domain.MyRequestType;
              import io.github.emmettl.rewrite.fixtures.domain.SomeErrorType;
              import io.github.emmettl.rewrite.fixtures.domain.SomeEventOrOther;
              import io.github.emmettl.rewrite.fixtures.handler.MyRequestHandler;
              import org.junit.jupiter.api.Test;
              import org.mockito.ArgumentCaptor;
              import org.mockito.Captor;
              import org.mockito.Mock;

              import static org.assertj.core.api.Assertions.assertThat;
              import static org.assertj.core.api.Assertions.assertThatThrownBy;
              import static org.mockito.ArgumentMatchers.eq;
              import static org.mockito.Mockito.verify;

              public class MyRequestHandlerTest {

                  @Mock
                  EventEmitter eventEmitter;
                  @Captor
                  private ArgumentCaptor<SomeEventOrOther> eventCaptor;

                  @Test
                  public void handleRequestWithError() {
                      MyRequestHandler myRequestHandler = new MyRequestHandler();
                      assertThatThrownBy(() -> myRequestHandler.handleRequest(new MyRequestType()))
                              .isInstanceOfSatisfying(RequestException.class, ex -> {
                                  SomeErrorType error = (SomeErrorType) ex.getReply();
                                  assertThat(error).isNotNull();
                              });
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
     * The other shape the routing value comes in: a bare field written by a {@code @BeforeEach}. The
     * field goes, its write goes with it, and the setup method — left with nothing to set up — goes
     * too, taking the {@code @BeforeEach} import.
     */
    @Test
    void dropsTheRoutingFieldAssignedInSetUp() {
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
              import org.junit.jupiter.api.BeforeEach;
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
                  private ArgumentCaptor<SomeErrorType> errorCaptor;

                  private MessageInfo messageInfo;

                  @BeforeEach
                  void setUp() {
                      messageInfo = new MessageInfo("corr");
                  }

                  @Test
                  public void handleRequestWithError() {
                      MyRequestHandler myRequestHandler = new MyRequestHandler();
                      myRequestHandler.handleRequest(new MyRequestType(), messageInfo);

                      verify(eventEmitter).emit(eq(MessageConstants.SEND_ERROR), errorCaptor.capture(), eq(messageInfo));
                      SomeErrorType error = errorCaptor.getValue();
                      assertThat(error).isNotNull();
                  }
              }
              """,
            """
              package io.github.emmettl.rewrite.fixtures.test;

              import io.github.emmettl.rewrite.fixtures.EventEmitter;
              import io.github.emmettl.rewrite.fixtures.common.RequestException;
              import io.github.emmettl.rewrite.fixtures.domain.MyRequestType;
              import io.github.emmettl.rewrite.fixtures.domain.SomeErrorType;
              import io.github.emmettl.rewrite.fixtures.handler.MyRequestHandler;
              import org.junit.jupiter.api.Test;
              import org.mockito.Mock;

              import static org.assertj.core.api.Assertions.assertThat;
              import static org.assertj.core.api.Assertions.assertThatThrownBy;

              public class MyRequestHandlerTest {

                  @Mock
                  EventEmitter eventEmitter;

                  @Test
                  public void handleRequestWithError() {
                      MyRequestHandler myRequestHandler = new MyRequestHandler();
                      assertThatThrownBy(() -> myRequestHandler.handleRequest(new MyRequestType()))
                              .isInstanceOfSatisfying(RequestException.class, ex -> {
                                  SomeErrorType error = (SomeErrorType) ex.getReply();
                                  assertThat(error).isNotNull();
                              });
                  }
              }
              """
          )
        );
    }

    /**
     * A {@code @BeforeEach} that does more than feed the routing field keeps everything else it does,
     * and keeps existing.
     */
    @Test
    void keepsTheRestOfASetUpItStillNeeds() {
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
              import org.junit.jupiter.api.BeforeEach;
              import org.junit.jupiter.api.Test;
              import org.mockito.ArgumentCaptor;
              import org.mockito.Captor;
              import org.mockito.Mock;
              import org.mockito.MockitoAnnotations;

              import static org.assertj.core.api.Assertions.assertThat;
              import static org.mockito.ArgumentMatchers.eq;
              import static org.mockito.Mockito.verify;

              public class MyRequestHandlerTest {

                  @Mock
                  EventEmitter eventEmitter;
                  @Captor
                  private ArgumentCaptor<SomeErrorType> errorCaptor;

                  private MessageInfo messageInfo;

                  @BeforeEach
                  void setUp() {
                      MockitoAnnotations.openMocks(this);
                      messageInfo = new MessageInfo("corr");
                  }

                  @Test
                  public void handleRequestWithError() {
                      MyRequestHandler myRequestHandler = new MyRequestHandler();
                      myRequestHandler.handleRequest(new MyRequestType(), messageInfo);

                      verify(eventEmitter).emit(eq(MessageConstants.SEND_ERROR), errorCaptor.capture(), eq(messageInfo));
                      SomeErrorType error = errorCaptor.getValue();
                      assertThat(error).isNotNull();
                  }
              }
              """,
            """
              package io.github.emmettl.rewrite.fixtures.test;

              import io.github.emmettl.rewrite.fixtures.EventEmitter;
              import io.github.emmettl.rewrite.fixtures.common.RequestException;
              import io.github.emmettl.rewrite.fixtures.domain.MyRequestType;
              import io.github.emmettl.rewrite.fixtures.domain.SomeErrorType;
              import io.github.emmettl.rewrite.fixtures.handler.MyRequestHandler;
              import org.junit.jupiter.api.BeforeEach;
              import org.junit.jupiter.api.Test;
              import org.mockito.Mock;
              import org.mockito.MockitoAnnotations;

              import static org.assertj.core.api.Assertions.assertThat;
              import static org.assertj.core.api.Assertions.assertThatThrownBy;

              public class MyRequestHandlerTest {

                  @Mock
                  EventEmitter eventEmitter;

                  @BeforeEach
                  void setUp() {
                      MockitoAnnotations.openMocks(this);
                  }

                  @Test
                  public void handleRequestWithError() {
                      MyRequestHandler myRequestHandler = new MyRequestHandler();
                      assertThatThrownBy(() -> myRequestHandler.handleRequest(new MyRequestType()))
                              .isInstanceOfSatisfying(RequestException.class, ex -> {
                                  SomeErrorType error = (SomeErrorType) ex.getReply();
                                  assertThat(error).isNotNull();
                              });
                  }
              }
              """
          )
        );
    }

    /**
     * The routing field is only dead if this was its last reader. A second test still passing it to
     * something keeps the field, its initialiser, and its import.
     */
    @Test
    void keepsARoutingFieldAnotherTestStillUses() {
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
                      SomeErrorType error = errorCaptor.getValue();
                      assertThat(error).isNotNull();
                  }

                  @Test
                  public void carriesTheCorrelationId() {
                      assertThat(messageInfo.correlationId()).isEqualTo("corr");
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
              import org.mockito.Mock;

              import static org.assertj.core.api.Assertions.assertThat;
              import static org.assertj.core.api.Assertions.assertThatThrownBy;

              public class MyRequestHandlerTest {

                  @Mock
                  EventEmitter eventEmitter;

                  private final MessageInfo messageInfo = new MessageInfo("corr");

                  @Test
                  public void handleRequestWithError() {
                      MyRequestHandler myRequestHandler = new MyRequestHandler();
                      assertThatThrownBy(() -> myRequestHandler.handleRequest(new MyRequestType()))
                              .isInstanceOfSatisfying(RequestException.class, ex -> {
                                  SomeErrorType error = (SomeErrorType) ex.getReply();
                                  assertThat(error).isNotNull();
                              });
                  }

                  @Test
                  public void carriesTheCorrelationId() {
                      assertThat(messageInfo.correlationId()).isEqualTo("corr");
                  }
              }
              """
          )
        );
    }

    /**
     * The same captor can serve a test this recipe does not migrate. Removing the field there would
     * break that test, so it survives even though its error round trip collapsed.
     */
    @Test
    void keepsACaptorAnotherTestStillUses() {
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
                      SomeErrorType error = errorCaptor.getValue();
                      assertThat(error).isNotNull();
                  }

                  @Test
                  public void alsoReportsTheFailureAsAnEvent() {
                      verify(eventEmitter).emit(eq("AnEvent"), errorCaptor.capture());

                      assertThat(errorCaptor.getValue()).isNotNull();
                  }
              }
              """,
            """
              package io.github.emmettl.rewrite.fixtures.test;

              import io.github.emmettl.rewrite.fixtures.EventEmitter;
              import io.github.emmettl.rewrite.fixtures.common.RequestException;
              import io.github.emmettl.rewrite.fixtures.domain.MyRequestType;
              import io.github.emmettl.rewrite.fixtures.domain.SomeErrorType;
              import io.github.emmettl.rewrite.fixtures.handler.MyRequestHandler;
              import org.junit.jupiter.api.Test;
              import org.mockito.ArgumentCaptor;
              import org.mockito.Captor;
              import org.mockito.Mock;

              import static org.assertj.core.api.Assertions.assertThat;
              import static org.assertj.core.api.Assertions.assertThatThrownBy;
              import static org.mockito.ArgumentMatchers.eq;
              import static org.mockito.Mockito.verify;

              public class MyRequestHandlerTest {

                  @Mock
                  EventEmitter eventEmitter;
                  @Captor
                  private ArgumentCaptor<SomeErrorType> errorCaptor;

                  @Test
                  public void handleRequestWithError() {
                      MyRequestHandler myRequestHandler = new MyRequestHandler();
                      assertThatThrownBy(() -> myRequestHandler.handleRequest(new MyRequestType()))
                              .isInstanceOfSatisfying(RequestException.class, ex -> {
                                  SomeErrorType error = (SomeErrorType) ex.getReply();
                                  assertThat(error).isNotNull();
                              });
                  }

                  @Test
                  public void alsoReportsTheFailureAsAnEvent() {
                      verify(eventEmitter).emit(eq("AnEvent"), errorCaptor.capture());

                      assertThat(errorCaptor.getValue()).isNotNull();
                  }
              }
              """
          )
        );
    }
}
