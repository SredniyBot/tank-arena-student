package edu.arena.client;

import edu.arena.core.model.Control;
import edu.arena.core.model.Vec2;
import edu.arena.protocol.JsonCodec;
import edu.arena.protocol.Messages;
import edu.arena.protocol.Messages.Command;
import edu.arena.protocol.Messages.CommandReceipt;
import edu.arena.protocol.Messages.Enroll;
import edu.arena.protocol.Messages.Enrollment;
import edu.arena.protocol.Messages.Info;
import edu.arena.protocol.Messages.LiveStanding;
import edu.arena.protocol.Messages.LobbyView;
import edu.arena.protocol.Messages.MatchView;
import edu.arena.protocol.Messages.Me;
import edu.arena.protocol.Messages.NameRequest;
import edu.arena.protocol.Messages.QueueReply;
import edu.arena.protocol.Messages.QueueRequest;
import edu.arena.protocol.Messages.ReplayPage;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/** Small typed HTTP adapter. Credentials are sent only to the validated server origin. */
public final class ArenaClient implements ArenaGateway, ReconGateway {
    // A server can echo a password even in an uppercase error code. Only protocol codes are public.
    private static final Set<String> SERVER_ERROR_CODES =
            Set.of(
                    "ADMIN_KEY_NOT_PASSWORD",
                    "ADMIN_REQUIRED",
                    "APPROVAL_REQUIRED",
                    "AUTH_INVALID",
                    "BANNED",
                    "BODY_TOO_LARGE",
                    "CAPACITY_REACHED",
                    "COMMAND_CONFLICT",
                    "ELIMINATED",
                    "ENCODING_REJECTED",
                    "INCOMPATIBLE_PROTOCOL",
                    "INTERNAL_ERROR",
                    "INVALID_AIM",
                    "INVALID_COMMAND",
                    "INVALID_INPUT",
                    "INVALID_JSON",
                    "INVALID_LENGTH",
                    "INVALID_MAP",
                    "INVALID_MODE",
                    "INVALID_NAME",
                    "INVALID_PAGE",
                    "INVALID_PASSWORD",
                    "INVALID_ROSTER",
                    "INVALID_SERVER_PASSWORD",
                    "IN_TOURNAMENT",
                    "IO_UNAVAILABLE",
                    "JSON_REQUIRED",
                    "LIVE_MODE",
                    "MATCH_FINISHED",
                    "MATCH_NOT_FOUND",
                    "METHOD_NOT_ALLOWED",
                    "NAME_LOCKED",
                    "NOT_FOUND",
                    "NOT_IN_ARENA",
                    "NOT_PARTICIPANT",
                    "ORIGIN_REJECTED",
                    "PASSWORD_NOT_CONFIGURED",
                    "PLAYER_BUSY",
                    "PLAYER_ELIMINATED",
                    "PLAYER_NOT_FOUND",
                    "PLAYER_OFFLINE",
                    "RATE_LIMITED",
                    "REPLAY_BUSY",
                    "RESPAWNING",
                    "RECON_PRIVATE",
                    "RECON_DISABLED",
                    "OBSERVATION_PENDING",
                    "REVOKED",
                    "ROUND_ACTIVE",
                    "SERVER_PASSWORD_CHANGED",
                    "STALE_TICK",
                    "STORAGE_UNAVAILABLE",
                    "TOURNAMENT_RUNNING",
                    "UNAUTHORIZED",
                    "UNKNOWN_PLAYER",
                    "URI_TOO_LONG",
                    "WAIT_IN_PROGRESS");
    private static final int MAX_RESPONSE_BYTES = 1024 * 1024;
    private static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(5);
    // A page can span four stored 64-frame chunks, each bounded to 16 MiB decoded.
    private static final int MAX_REPLAY_RESPONSE_BYTES = 64 * 1024 * 1024;
    private final HttpClient http;
    private final URI base;
    private final String token;
    private final JsonCodec json = new JsonCodec();

    public ArenaClient(HttpClient http, URI server, String token) {
        this(http, server, token, false);
    }

    public ArenaClient(HttpClient http, URI server, String token, boolean allowInsecureLan) {
        this.http = Objects.requireNonNull(http, "http");
        Objects.requireNonNull(server, "server");
        if (http.followRedirects() != HttpClient.Redirect.NEVER) {
            throw new IllegalArgumentException("HTTP redirects must be disabled");
        }
        String scheme =
                server.getScheme() == null ? "" : server.getScheme().toLowerCase(Locale.ROOT);
        String host = server.getHost();
        boolean loopback =
                host != null
                        && (host.equalsIgnoreCase("localhost")
                                || host.equals("127.0.0.1")
                                || host.equals("[::1]")
                                || host.equals("::1"));
        if (host == null
                || server.getRawUserInfo() != null
                || server.getRawQuery() != null
                || server.getRawFragment() != null
                || !(server.getRawPath().isEmpty() || server.getRawPath().equals("/"))
                || !(scheme.equals("https")
                        || scheme.equals("http") && (loopback || allowInsecureLan))
                || server.getPort() == 0
                || server.getPort() > 65535) {
            throw new IllegalArgumentException(
                    "Use a server origin such as https://arena.example; plain HTTP requires loopback or explicit LAN permission");
        }
        if (token != null && !token.matches("[A-Za-z0-9_-]{43}")) {
            throw new IllegalArgumentException("Invalid token file contents");
        }
        this.base = server.resolve("/api/v2/");
        this.token = token;
        if (token != null) {
            prepareCommandCodec();
        }
    }

    /** Resolve command codecs locally before the client can join a timed decision window. */
    private void prepareCommandCodec() {
        try {
            json.write(new Command(0, Control.idle(new Vec2(0, 0))));
            json.read(
                    "{\"acceptedTick\":0,\"applyAtTick\":2,\"expiresAtTick\":4,\"duplicate\":false}"
                            .getBytes(StandardCharsets.US_ASCII),
                    CommandReceipt.class);
        } catch (IOException failure) {
            throw new IllegalStateException("Failed to prepare command protocol", failure);
        }
    }

    public Info info() throws IOException, InterruptedException {
        return request("GET", "info", null, Info.class);
    }

    public Me me() throws IOException, InterruptedException {
        return request("GET", "me", null, Me.class);
    }

    public Me rename(String name) throws IOException, InterruptedException {
        return request("POST", "me/name", new NameRequest(name), Me.class);
    }

    public java.util.List<LiveStanding> liveLeaderboard() throws IOException, InterruptedException {
        return java.util.List.of(request("GET", "live/leaderboard", null, LiveStanding[].class));
    }

    public LobbyView lobby() throws IOException, InterruptedException {
        return request("GET", "lobby", null, LobbyView.class);
    }

    public Enrollment enroll(String password, String name)
            throws IOException, InterruptedException {
        return request("POST", "enroll", new Enroll(password, name), Enrollment.class);
    }

    public QueueReply queue(String mode, String opponent, String map)
            throws IOException, InterruptedException {
        return request("POST", "queue", new QueueRequest(mode, opponent, map), QueueReply.class);
    }

    public void cancelQueue() throws IOException, InterruptedException {
        request("DELETE", "queue", null, Void.class);
    }

    public MatchView match(String id) throws IOException, InterruptedException {
        return request("GET", "matches/" + id(id), null, MatchView.class);
    }

    @Override
    public MatchView observation(String id) throws IOException, InterruptedException {
        return request("GET", "matches/" + id(id) + "/observation", null, MatchView.class);
    }

    @Override
    public edu.arena.protocol.Messages.ReconFrame recon(String id)
            throws IOException, InterruptedException {
        return request(
                "GET",
                "matches/" + id(id) + "/recon",
                null,
                edu.arena.protocol.Messages.ReconFrame.class);
    }

    @Override
    public edu.arena.protocol.Messages.ReconFrame reconAfter(String id, int afterTick)
            throws IOException, InterruptedException {
        if (afterTick < 0) {
            throw new IllegalArgumentException("Decision tick cannot be negative");
        }
        return request(
                "GET",
                "matches/" + id(id) + "/recon?after=" + afterTick,
                null,
                edu.arena.protocol.Messages.ReconFrame.class);
    }

    public edu.arena.protocol.Messages.ReconFrame reconPreparation(Duration timeout)
            throws IOException, InterruptedException {
        Objects.requireNonNull(timeout, "timeout");
        if (timeout.isNegative() || timeout.isZero()) {
            throw new IllegalArgumentException("timeout must be positive");
        }
        return request(
                "GET",
                "recon/prepare",
                null,
                edu.arena.protocol.Messages.ReconFrame.class,
                timeout.compareTo(REQUEST_TIMEOUT) < 0 ? timeout : REQUEST_TIMEOUT);
    }

    public CommandReceipt command(String id, Command command)
            throws IOException, InterruptedException {
        return request("POST", "matches/" + id(id) + "/commands", command, CommandReceipt.class);
    }

    public void forfeit(String id) throws IOException, InterruptedException {
        request("POST", "matches/" + id(id) + "/forfeit", null, Void.class);
    }

    public ReplayPage replay(String id, int from, int limit)
            throws IOException, InterruptedException {
        if (from < 0 || limit < 1 || limit > 200) {
            throw new IllegalArgumentException("Invalid replay page");
        }
        return request(
                "GET",
                "matches/" + id(id) + "/replay?from=" + from + "&limit=" + limit,
                null,
                ReplayPage.class);
    }

    private <T> T request(String method, String path, Object value, Class<T> type)
            throws IOException, InterruptedException {
        return request(method, path, value, type, REQUEST_TIMEOUT);
    }

    private <T> T request(String method, String path, Object value, Class<T> type, Duration timeout)
            throws IOException, InterruptedException {
        byte[] body = value == null ? new byte[0] : json.write(value);
        if (body.length > 4096) {
            throw new IOException("Request exceeds the protocol body limit");
        }
        var builder =
                HttpRequest.newBuilder(base.resolve(path))
                        .timeout(timeout)
                        .header("Accept", "application/json")
                        .header(Messages.VERSION_HEADER, Messages.VERSION);
        if (token != null) {
            builder.header("Authorization", "Bearer " + token);
        }
        if (value != null) {
            builder.header("Content-Type", "application/json");
        }
        builder.method(
                method,
                body.length == 0
                        ? HttpRequest.BodyPublishers.noBody()
                        : HttpRequest.BodyPublishers.ofByteArray(body));
        int maximum = type == ReplayPage.class ? MAX_REPLAY_RESPONSE_BYTES : MAX_RESPONSE_BYTES;
        var pending =
                http.sendAsync(builder.build(), ignored -> new LimitedBodySubscriber(maximum));
        HttpResponse<byte[]> response;
        try {
            // HttpRequest.timeout alone may stop at headers; bound completion of the entire body as
            // well.
            response = pending.get(Math.max(1, timeout.toMillis()), TimeUnit.MILLISECONDS);
        } catch (TimeoutException expired) {
            pending.cancel(true);
            throw new HttpTimeoutException(
                    "Server response did not complete within the request deadline");
        } catch (InterruptedException interrupted) {
            pending.cancel(true);
            throw interrupted;
        } catch (ExecutionException failed) {
            if (failed.getCause() instanceof IOException failure) {
                throw failure;
            }
            throw new IOException("HTTP exchange failed", failed.getCause());
        }
        int status = response.statusCode();
        if (status >= 300 && status < 400) {
            throw new ArenaException(
                    status,
                    "REDIRECT_REFUSED",
                    "Server redirects are refused; use its final HTTPS address",
                    0);
        }
        String contentType =
                response.headers().firstValue("Content-Type").orElse("").toLowerCase(Locale.ROOT);
        if (status < 200 || status >= 300) {
            String code = "HTTP_" + status;
            if (contentType.startsWith("application/json")) {
                try {
                    var error = json.read(response.body(), edu.arena.protocol.Messages.Error.class);
                    if (error != null
                            && error.code() != null
                            && SERVER_ERROR_CODES.contains(error.code())) {
                        code = error.code();
                    }
                } catch (IOException ignored) {
                }
            }
            int retryAfter = 0;
            try {
                retryAfter =
                        Math.clamp(
                                Integer.parseInt(
                                        response.headers().firstValue("Retry-After").orElse("0")),
                                0,
                                60);
            } catch (NumberFormatException ignored) {
            }
            throw new ArenaException(
                    status,
                    code,
                    rejectionMessage(code) + " [" + code + ", HTTP " + status + "]",
                    retryAfter);
        }
        if (type == Void.class) {
            return null;
        }
        if (!contentType.startsWith("application/json")) {
            throw new IOException("Server returned an unexpected content type");
        }
        return json.read(response.body(), type);
    }

    private static String rejectionMessage(String code) {
        // Use local guidance: an untrusted response may echo a credential or terminal controls.
        return switch (code) {
            case "INCOMPATIBLE_PROTOCOL" ->
                    "Сервер принимает только актуальный протокол. Обновите проект из GitHub, перезагрузите Maven и запустите Main снова.";
            case "ADMIN_KEY_NOT_PASSWORD" ->
                    "Введён ключ преподавателя. Для подключения нужен общий пароль сервера; его преподаватель задаёт в админке и сообщает группе.";
            case "INVALID_SERVER_PASSWORD" ->
                    "Пароль сервера не принят. Уточните актуальный пароль у преподавателя и запустите Main снова.";
            case "PASSWORD_NOT_CONFIGURED" ->
                    "Пароль сервера ещё не установлен. Преподавателю нужно задать его в админке.";
            case "INVALID_PASSWORD" ->
                    "Пароль должен содержать от 8 до 128 символов без переводов строк и управляющих символов.";
            case "SERVER_PASSWORD_CHANGED" ->
                    "Пароль сервера был изменён во время подключения. Уточните новый пароль и запустите Main снова.";
            case "RATE_LIMITED" ->
                    "Слишком много запросов. Подождите несколько секунд и повторите подключение. Если отказ повторяется у всей группы, сообщите преподавателю.";
            case "APPROVAL_REQUIRED" ->
                    "Регистрация принята. Дождитесь допуска преподавателя; повторная регистрация не нужна.";
            case "REVOKED", "BANNED" ->
                    "Доступ закрыт преподавателем. Попросите восстановить доступ к прежнему аккаунту; сохранённый ключ менять не нужно.";
            case "CAPACITY_REACHED" ->
                    "На сервере нет свободных мест для регистрации. Попросите преподавателя проверить вместимость сервера.";
            case "INVALID_NAME" ->
                    "Имя танка: от 1 до 32 символов. Допустимы буквы, цифры, пробел, точка, дефис и подчёркивание.";
            case "UNAUTHORIZED", "AUTH_INVALID" ->
                    "Сервер не узнаёт ключ участника. Проверьте адрес сервера и обратитесь к преподавателю.";
            case "ADMIN_REQUIRED" ->
                    "Для этого действия нужен ключ преподавателя. Управление доступно в браузере в разделе «Преподавателю».";
            case "ORIGIN_REJECTED" ->
                    "Источник запроса не разрешён. Откройте арену по адресу запущенного сервера.";
            case "STORAGE_UNAVAILABLE", "IO_UNAVAILABLE" ->
                    "Сервер не может сохранить данные. Повторите подключение позже; при повторном отказе обратитесь к преподавателю.";
            default -> "Сервер отклонил запрос. Передайте преподавателю код ошибки.";
        };
    }

    private static String id(String value) {
        if (value == null || !value.matches("[A-Za-z0-9_-]{1,80}")) {
            throw new IllegalArgumentException("Invalid match identifier");
        }
        return value;
    }
}
