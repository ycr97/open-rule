package io.openrule.api.dto;

public record ErrorBody(String code, String message, long timestamp) {
    public static ErrorBody of(String code, String message) {
        return new ErrorBody(code, message, System.currentTimeMillis());
    }
}
