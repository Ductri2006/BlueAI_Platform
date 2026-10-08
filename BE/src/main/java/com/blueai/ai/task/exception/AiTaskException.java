package com.blueai.ai.task.exception;

import org.springframework.http.HttpStatus;

public class AiTaskException extends RuntimeException {
    public final HttpStatus status;
    public final String code;

    public AiTaskException(HttpStatus status, String code, String message) {
        super(message);
        this.status = status;
        this.code = code;
    }
}
