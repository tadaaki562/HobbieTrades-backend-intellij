package com.hobbietrades.backend.service.roboflow;

public class RoboflowWorkflowException extends RuntimeException {

    private final int statusCode;

    public RoboflowWorkflowException(String message) {
        super(message);
        this.statusCode = 0;
    }

    public RoboflowWorkflowException(String message, int statusCode) {
        super(message);
        this.statusCode = statusCode;
    }

    public RoboflowWorkflowException(String message, Throwable cause) {
        super(message, cause);
        this.statusCode = 0;
    }

    public int getStatusCode() {
        return statusCode;
    }
}
