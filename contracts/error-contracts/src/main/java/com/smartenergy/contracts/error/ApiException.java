package com.smartenergy.contracts.error;

import java.util.List;

/**
 * Exception metier unique : levee par les services, convertie en application/problem+json
 * par le GlobalExceptionHandler de platform-web.
 */
public class ApiException extends RuntimeException {

    private final ErrorCode code;
    private final List<FieldViolation> violations;
    private final Integer retryAfterSeconds;

    public ApiException(ErrorCode code, String detail) {
        this(code, detail, List.of(), null);
    }

    public ApiException(ErrorCode code, String detail, List<FieldViolation> violations, Integer retryAfterSeconds) {
        super(detail);
        this.code = code;
        this.violations = violations == null ? List.of() : List.copyOf(violations);
        this.retryAfterSeconds = retryAfterSeconds;
    }

    public static ApiException of(ErrorCode code, String detail) {
        return new ApiException(code, detail);
    }

    /** 503 avec en-tete Retry-After (ex. Redis indisponible). */
    public static ApiException unavailable(String detail, int retryAfterSeconds) {
        return new ApiException(ErrorCode.BROKER_UNAVAILABLE, detail, List.of(), retryAfterSeconds);
    }

    public ErrorCode code() { return code; }

    public List<FieldViolation> violations() { return violations; }

    public Integer retryAfterSeconds() { return retryAfterSeconds; }
}
