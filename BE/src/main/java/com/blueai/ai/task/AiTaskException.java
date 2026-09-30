package com.blueai.ai.task;

import org.springframework.http.HttpStatus;

class AiTaskException extends RuntimeException {
    final HttpStatus status;
    final String code;
    AiTaskException(HttpStatus status,String code,String message){super(message);this.status=status;this.code=code;}
}
