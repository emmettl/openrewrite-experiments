package io.github.emmettl.rewrite.fixtures.test;


import io.github.emmettl.rewrite.fixtures.EventEmitter;
import io.github.emmettl.rewrite.fixtures.common.MessageConstants;
import io.github.emmettl.rewrite.fixtures.domain.MessageInfo;
import io.github.emmettl.rewrite.fixtures.domain.MyRequestType;
import io.github.emmettl.rewrite.fixtures.domain.MyResponseType;
import io.github.emmettl.rewrite.fixtures.handler.MyRequestHandler;
import io.github.emmettl.rewrite.fixtures.handler.RefactoredRequestHandler;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.Mock;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;

public class RefactoredRequestHandlerTest {

    @Mock
    EventEmitter eventEmitter;

    @Test
    public void handleRequestTest() {
        var myRequestHandler = new RefactoredRequestHandler();
        var response = myRequestHandler.handleRequest(new MyRequestType());

        assertThat(response).isNotNull();
    }
}
