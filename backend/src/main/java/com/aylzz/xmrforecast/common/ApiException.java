package com.aylzz.xmrforecast.common;

/** Error de negocio con codigo estable, mapeado a un status HTTP concreto. */
public class ApiException extends RuntimeException {

    private final int status;
    private final String code;

    public ApiException(int status, String code, String message) {
        super(message);
        this.status = status;
        this.code = code;
    }

    public int getStatus() {
        return status;
    }

    public String getCode() {
        return code;
    }

    public static ApiException badRequest(String code, String message) {
        return new ApiException(400, code, message);
    }

    public static ApiException unauthorized(String code, String message) {
        return new ApiException(401, code, message);
    }

    public static ApiException forbidden(String code, String message) {
        return new ApiException(403, code, message);
    }

    public static ApiException notFound(String code, String message) {
        return new ApiException(404, code, message);
    }

    public static ApiException conflict(String code, String message) {
        return new ApiException(409, code, message);
    }

    public static ApiException tooManyRequests(String code, String message) {
        return new ApiException(429, code, message);
    }

    public static ApiException unprocessable(String code, String message) {
        return new ApiException(422, code, message);
    }

    public static ApiException badGateway(String code, String message) {
        return new ApiException(502, code, message);
    }

    public static ApiException unavailable(String code, String message) {
        return new ApiException(503, code, message);
    }
}