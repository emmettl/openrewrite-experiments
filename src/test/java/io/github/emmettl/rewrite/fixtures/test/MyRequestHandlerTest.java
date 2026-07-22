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
    private ArgumentCaptor<MyResponseType> reponseCaptor;

    @Test
    public void handleRequestTest() {
        MyRequestHandler myRequestHandler = new MyRequestHandler();
        myRequestHandler.handleRequest(new MyRequestType(), new MessageInfo("foo"));

        verify(eventEmitter).emit(eq(MessageConstants.SEND_REPLY), reponseCaptor.capture(), any(MessageInfo.class));

        var response = reponseCaptor.getValue();

        assertThat(response).isNotNull();
    }
}
