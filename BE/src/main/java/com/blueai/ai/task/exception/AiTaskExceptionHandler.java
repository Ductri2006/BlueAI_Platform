package com.blueai.ai.task.exception;

import com.blueai.ai.task.controller.AiTaskController;
import com.blueai.ai.task.controller.IntegrationClientController;
import com.blueai.ai.task.controller.IntegrationBotController;
import com.blueai.ai.task.controller.PublishedBotApiController;

import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MissingRequestHeaderException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.util.Map;
import java.util.UUID;

@RestControllerAdvice(assignableTypes = {AiTaskController.class, IntegrationClientController.class, IntegrationBotController.class, PublishedBotApiController.class})
@Order(Ordered.HIGHEST_PRECEDENCE)
public class AiTaskExceptionHandler {
    @ExceptionHandler(AiTaskException.class)
    ResponseEntity<?> handle(AiTaskException e) {
        var builder = ResponseEntity.status(e.status);
        if (e.status.value() == 429) builder.header(HttpHeaders.RETRY_AFTER, "60");
        return builder.body(
                Map.of(
                        "error",
                        Map.of(
                                "code",
                                e.code,
                                "message",
                                e.getMessage(),
                                "traceId",
                                UUID.randomUUID().toString())));
    }

    @ExceptionHandler({MissingRequestHeaderException.class, HttpMessageNotReadableException.class})
    ResponseEntity<?> malformed(Exception e) {
        if (e instanceof HttpMessageNotReadableException && causedByLimit(e))
            return ResponseEntity.status(413)
                    .body(
                            Map.of(
                                    "error",
                                    Map.of(
                                            "code",
                                            "REQUEST_TOO_LARGE",
                                            "message",
                                            "AI task request exceeds 64 KiB.",
                                            "traceId",
                                            UUID.randomUUID().toString())));
        return ResponseEntity.badRequest()
                .body(
                        Map.of(
                                "error",
                                Map.of(
                                        "code",
                                        e instanceof MissingRequestHeaderException
                                                ? "INVALID_IDEMPOTENCY_KEY"
                                                : "MALFORMED_JSON",
                                        "message",
                                        e instanceof MissingRequestHeaderException
                                                ? "Idempotency-Key header is required."
                                                : "Request JSON is malformed.",
                                        "traceId",
                                        UUID.randomUUID().toString())));
    }

    private boolean causedByLimit(Throwable error) {
        for (Throwable cause = error; cause != null; cause = cause.getCause())
            if ("AI_TASK_REQUEST_TOO_LARGE".equals(cause.getMessage())) return true;
        return false;
    }
}
