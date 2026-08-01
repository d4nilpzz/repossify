package dev.d4nilpzz.http;

/**
 * Uniform error body for every JSON endpoint. Registered globally in
 * {@code Repossify} so handlers can just throw and get a consistent shape.
 */
public record ErrorResponse(int status, String error, String message) {

    public static ErrorResponse of(int status, String message) {
        return new ErrorResponse(status, reason(status), message);
    }

    private static String reason(int status) {
        return switch (status) {
            case 400 -> "Bad Request";
            case 401 -> "Unauthorized";
            case 403 -> "Forbidden";
            case 404 -> "Not Found";
            case 405 -> "Method Not Allowed";
            case 409 -> "Conflict";
            case 413 -> "Payload Too Large";
            case 416 -> "Range Not Satisfiable";
            case 502 -> "Bad Gateway";
            case 507 -> "Insufficient Storage";
            default -> status >= 500 ? "Internal Server Error" : "Error";
        };
    }
}
