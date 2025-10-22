package br.com.gabxdev.lambda.runtime;

public record Context(String requestId, long deadlineMs, String invokedArn, String traceId) {
}

