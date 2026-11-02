package edu.arena.server.http;

import com.sun.net.httpserver.HttpExchange;
import edu.arena.protocol.JsonCodec;
import edu.arena.protocol.Messages;
import edu.arena.server.match.ApplicationFailure;
import java.io.IOException;
import java.util.Locale;

/** Framing, bounded reads and response security headers. */
final class JsonHttp {
    private static final int MAX_BODY = 4096;
    private final JsonCodec json;

    JsonHttp(JsonCodec json) {
        this.json = json;
    }

    <T> T body(HttpExchange x, Class<T> type) throws IOException {
        var contentType = x.getRequestHeaders().getFirst("Content-Type");
        if (contentType == null
                || !contentType
                        .toLowerCase(Locale.ROOT)
                        .split(";", 2)[0]
                        .strip()
                        .equals("application/json")) {
            throw new ApplicationFailure(
                    415, "JSON_REQUIRED", "Ожидается Content-Type: application/json.");
        }
        var encoding = x.getRequestHeaders().getFirst("Content-Encoding");
        if (encoding != null && !encoding.equalsIgnoreCase("identity")) {
            throw new ApplicationFailure(
                    415, "ENCODING_REJECTED", "Сжатые тела запросов не поддерживаются.");
        }
        var length = x.getRequestHeaders().getFirst("Content-Length");
        if (length != null) {
            try {
                long size = Long.parseLong(length);
                if (size < 0 || size > MAX_BODY) {
                    throw new ApplicationFailure(
                            413, "BODY_TOO_LARGE", "Тело запроса превышает 4 КиБ.");
                }
            } catch (NumberFormatException e) {
                throw new ApplicationFailure(400, "INVALID_LENGTH", "Некорректный размер запроса.");
            }
        }
        var bytes = x.getRequestBody().readNBytes(MAX_BODY + 1);
        if (bytes.length > MAX_BODY) {
            throw new ApplicationFailure(413, "BODY_TOO_LARGE", "Тело запроса превышает 4 КиБ.");
        }
        try {
            var value = json.read(bytes, type);
            if (value == null) {
                throw new IllegalArgumentException("Null root");
            }
            return value;
        } catch (IOException | IllegalArgumentException e) {
            throw new ApplicationFailure(
                    400, "INVALID_JSON", "Проверьте поля, типы и диапазоны значений JSON.");
        }
    }

    void send(HttpExchange x, int status, Object value) throws IOException {
        sendBytes(x, status, json.write(value), "application/json; charset=utf-8");
    }

    void sendBytes(HttpExchange x, int status, byte[] bytes, String contentType)
            throws IOException {
        var h = x.getResponseHeaders();
        h.set("Content-Type", contentType);
        h.set("Cache-Control", "no-store");
        h.set(Messages.VERSION_HEADER, Messages.VERSION);
        h.set("X-Content-Type-Options", "nosniff");
        h.set("X-Frame-Options", "DENY");
        h.set("Referrer-Policy", "no-referrer");
        h.set(
                "Content-Security-Policy",
                "default-src 'self'; script-src 'self'; style-src 'self'; img-src 'self' data:; connect-src 'self'; frame-ancestors 'none'; base-uri 'none'; form-action 'self'");
        h.set("Permissions-Policy", "camera=(), microphone=(), geolocation=()");
        if (status == 429 || status == 503) {
            h.set("Retry-After", "1");
        }
        if (status == 401) {
            h.set("WWW-Authenticate", "Bearer");
        }
        x.sendResponseHeaders(status, bytes.length);
        x.getResponseBody().write(bytes);
    }
}
