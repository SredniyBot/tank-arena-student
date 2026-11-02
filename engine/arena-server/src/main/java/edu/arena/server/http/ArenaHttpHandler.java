package edu.arena.server.http;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import edu.arena.protocol.JsonCodec;
import edu.arena.protocol.Messages;
import edu.arena.protocol.Messages.Command;
import edu.arena.protocol.Messages.Enroll;
import edu.arena.protocol.Messages.Info;
import edu.arena.protocol.Messages.MatchView;
import edu.arena.protocol.Messages.NameRequest;
import edu.arena.protocol.Messages.QueueRequest;
import edu.arena.protocol.Messages.RulesReset;
import edu.arena.protocol.Messages.RulesUpdate;
import edu.arena.protocol.Messages.ServerAccess;
import edu.arena.protocol.Messages.ServerPassword;
import edu.arena.protocol.Messages.TournamentRequest;
import edu.arena.server.config.ServerConfig;
import edu.arena.server.match.ApplicationFailure;
import edu.arena.server.match.MatchCoordinator;
import edu.arena.server.security.IdentityService;
import edu.arena.server.security.PlayerIdentity;
import edu.arena.server.security.SecurityFailure;
import java.io.IOException;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** HTTP adapter only: authentication, typed requests and application-service calls. */
public final class ArenaHttpHandler implements HttpHandler {
    private record Asset(byte[] bytes, String contentType) {}

    private final ServerConfig config;
    private final IdentityService identities;
    private final MatchCoordinator matches;
    private final RequestGuard guard;
    private final JsonHttp http;
    private final JsonCodec json;

    private record SnapshotBytes(MatchView view, byte[] bytes) {}

    private SnapshotBytes snapshot;
    private final java.util.concurrent.Semaphore replayCapacity =
            new java.util.concurrent.Semaphore(1);
    private final Map<String, Asset> assets = new HashMap<>();

    public ArenaHttpHandler(
            ServerConfig config,
            IdentityService identities,
            MatchCoordinator matches,
            JsonCodec json)
            throws IOException {
        this.config = config;
        this.identities = identities;
        this.matches = matches;
        this.json = json;
        guard = new RequestGuard(config);
        http = new JsonHttp(json);
        for (var name :
                List.of(
                        "index.html",
                        "styles.css",
                        "app.js",
                        "renderer.js",
                        "frame-model.js",
                        "team-roster.js",
                        "teacher-rules.js",
                        "live-timing.js",
                        "manual-input.js")) {
            try (var input = getClass().getResourceAsStream("/web/" + name)) {
                if (input == null) {
                    throw new IOException("Missing web asset: " + name);
                }
                var type =
                        name.endsWith("html")
                                ? "text/html; charset=utf-8"
                                : name.endsWith("css")
                                        ? "text/css; charset=utf-8"
                                        : "text/javascript; charset=utf-8";
                assets.put("/" + name, new Asset(input.readAllBytes(), type));
            }
        }
        for (var name :
                List.of(
                        "combat.html",
                        "combat.css",
                        "combat.js",
                        "recon.html",
                        "recon-viewer.js",
                        "recon-demo.json",
                        "recon.css",
                        "recon-view-model.js")) {
            try (var input = getClass().getResourceAsStream("/web/" + name)) {
                if (input != null) {
                    var type =
                            name.endsWith("html")
                                    ? "text/html; charset=utf-8"
                                    : name.endsWith("json")
                                            ? "application/json; charset=utf-8"
                                            : name.endsWith("css")
                                                    ? "text/css; charset=utf-8"
                                                    : "text/javascript; charset=utf-8";
                    assets.put("/" + name, new Asset(input.readAllBytes(), type));
                }
            }
        }
        assets.put("/", assets.get("/index.html"));
        // Browser and Java clients share one version source; no separate JS version literal.
        String browserProtocol =
                "export const PROTOCOL_VERSION = \""
                        + Messages.VERSION
                        + "\";\nexport const PROTOCOL_HEADER = \""
                        + Messages.VERSION_HEADER
                        + "\";\nexport const INDEPENDENT_COMBAT = "
                        + config.live().combatEnabled()
                        + ";\n";
        assets.put(
                "/protocol.js",
                new Asset(
                        browserProtocol.getBytes(StandardCharsets.UTF_8),
                        "text/javascript; charset=utf-8"));
    }

    @Override
    public void handle(HttpExchange exchange) {
        try {
            guard.before(exchange);
            guard.requireProtocol(exchange);
            route(exchange);
        } catch (ApplicationFailure e) {
            error(exchange, e.status(), e.code(), e.getMessage());
        } catch (SecurityFailure e) {
            error(exchange, e.status(), e.code(), e.getMessage());
        } catch (IllegalArgumentException e) {
            error(exchange, 400, "INVALID_INPUT", "Недопустимое значение запроса.");
        } catch (IOException e) {
            error(
                    exchange,
                    503,
                    "IO_UNAVAILABLE",
                    "Операция не завершена. Повторите попытку; при повторном отказе обратитесь к преподавателю.");
        } catch (RuntimeException e) {
            error(exchange, 500, "INTERNAL_ERROR", "Сервер не смог выполнить запрос.");
        } finally {
            exchange.close();
        }
    }

    private void route(HttpExchange x) throws IOException {
        var path = x.getRequestURI().getRawPath();
        var method = x.getRequestMethod();
        if (!path.startsWith("/api/v2/")) {
            if (!method.equals("GET")) {
                throw new ApplicationFailure(405, "METHOD_NOT_ALLOWED", "Используйте GET.");
            }
            var asset = assets.get(path);
            if (asset == null) {
                throw missing();
            }
            http.sendBytes(x, 200, asset.bytes(), asset.contentType());
            return;
        }
        var action = path.substring("/api/v2/".length());
        if (action.startsWith("admin/")) {
            admin(x, action.substring(6), method);
            return;
        }
        if (action.equals("info") && method.equals("GET")) {
            http.send(
                    x,
                    200,
                    new Info(
                            Messages.VERSION,
                            config.game().simulationHz(),
                            config.game().maxStepRate(),
                            Math.max(25, (config.game().periodMillis() + 1) / 2),
                            config.network().spectatorPollMillis(),
                            config.game().periodMillis() * config.game().controlPeriodTicks(),
                            List.of("commons"),
                            config.capacity().participants(),
                            config.game().lobbyWaitMillis(),
                            config.game().controlPeriodTicks(),
                            config.live().reconEnabled()));
            return;
        }
        if (action.equals("enroll") && method.equals("POST")) {
            guard.enrollment(x);
            var request = http.body(x, Enroll.class);
            var result = identities.enroll(request.password(), request.name());
            http.send(
                    x,
                    201,
                    new Messages.Enrollment(result.playerId(), result.name(), result.token()));
            return;
        }
        if (action.equals("matches") && method.equals("GET")) {
            http.send(x, 200, matches.list());
            return;
        }
        if (action.equals("lobby") && method.equals("GET")) {
            http.send(x, 200, matches.lobby());
            return;
        }
        if (action.equals("leaderboard") && method.equals("GET")) {
            http.send(x, 200, matches.leaderboard());
            return;
        }
        if (action.equals("live/leaderboard") && method.equals("GET")) {
            protectReconSnapshot(x);
            http.send(x, 200, matches.liveLeaderboard());
            return;
        }
        if (action.equals("recon/prepare") && method.equals("GET")) {
            guard.beginPreparation();
            try {
                var participant = player(x);
                if (!participant.approved()) {
                    throw IdentityService.approvalRequired();
                }
                if (x.getRequestURI().getRawQuery() != null) {
                    throw new ApplicationFailure(
                            400, "INVALID_INPUT", "Некорректный запрос подготовки.");
                }
                http.send(x, 200, matches.reconPreparation(participant.id()));
            } finally {
                guard.endPreparation();
            }
            return;
        }
        if (action.equals("recon/leaderboard") && method.equals("GET")) {
            http.send(x, 200, matches.reconLeaderboard());
            return;
        }
        if (action.equals("me/name") && method.equals("POST")) {
            var p = player(x);
            var body = http.body(x, NameRequest.class);
            var renamed =
                    matches.rename(
                            p.id(),
                            body.name(),
                            () -> identities.player(p.id()),
                            () -> identities.rename(p.id(), body.name()));
            http.send(x, 200, matches.me(renamed));
            return;
        }
        if (action.equals("me") && method.equals("GET")) {
            http.send(x, 200, matches.me(player(x)));
            return;
        }
        if (action.equals("queue")) {
            var p = player(x);
            if (method.equals("POST")) {
                var request = http.body(x, QueueRequest.class);
                http.send(x, 200, matches.enqueueCurrent(() -> identities.player(p.id()), request));
                return;
            }
            if (method.equals("DELETE")) {
                matches.cancelQueue(p.id());
                http.send(x, 200, Map.of());
                return;
            }
        }
        var segments = action.split("/", -1);
        if (segments.length >= 2 && segments[0].equals("matches")) {
            var id = segments[1];
            if (!id.matches("[0-9a-f-]{36}")) {
                throw missing();
            }
            if (segments.length == 2 && method.equals("GET")) {
                protectReconSnapshot(x);
                http.sendBytes(
                        x, 200, snapshotBytes(matches.view(id)), "application/json; charset=utf-8");
                return;
            }
            if (segments.length == 3 && segments[2].equals("observation") && method.equals("GET")) {
                protectReconSnapshot(x);
                http.send(x, 200, matches.observation(id));
                return;
            }
            if (segments.length == 3 && segments[2].equals("recon") && method.equals("GET")) {
                String parameters = x.getRequestURI().getRawQuery();
                if (parameters != null && parameters.matches("after=[0-9]{1,10}")) {
                    var participant = player(x);
                    if (!participant.approved()) {
                        throw IdentityService.approvalRequired();
                    }
                    http.send(
                            x,
                            200,
                            matches.reconObservationAfter(
                                    id,
                                    participant.id(),
                                    Integer.parseInt(parameters.substring(6))));
                } else if (parameters != null) {
                    requireAdmin(x);
                    if (!parameters.matches("slot=[0-9]{1,2}")) {
                        throw new IllegalArgumentException("Expected one slot parameter");
                    }
                    http.send(
                            x,
                            200,
                            matches.reconObservation(
                                    id, Integer.parseInt(parameters.substring(5))));
                } else {
                    var participant = player(x);
                    if (!participant.approved()) {
                        throw IdentityService.approvalRequired();
                    }
                    http.send(x, 200, matches.reconObservation(id, participant.id()));
                }
                return;
            }
            if (segments.length == 3 && segments[2].equals("replay") && method.equals("GET")) {
                protectReconSnapshot(x);
                String identity = null;
                if (x.getRequestHeaders().containsKey("Authorization")) {
                    if (identities.isAdmin(guard.bearer(x))) {
                        guard.principal("admin");
                        identity = "admin";
                    } else {
                        identity = player(x).id();
                    }
                }
                guard.replay(x, identity);
                var query = query(x);
                if (!replayCapacity.tryAcquire()) {
                    throw new ApplicationFailure(
                            429,
                            "REPLAY_BUSY",
                            "Повтор сейчас загружается. Повторите запрос позже.");
                }
                try {
                    http.send(
                            x,
                            200,
                            matches.replay(
                                    id,
                                    Integer.parseInt(query.getOrDefault("from", "0")),
                                    Integer.parseInt(query.getOrDefault("limit", "32"))));
                } finally {
                    replayCapacity.release();
                }
                return;
            }
            if (segments.length == 3 && segments[2].equals("commands") && method.equals("POST")) {
                var p = player(x);
                if (!p.approved()) {
                    throw IdentityService.approvalRequired();
                }
                var command = http.body(x, Command.class);
                http.send(x, 200, matches.submit(id, p.id(), command));
                return;
            }
            if (segments.length == 3 && segments[2].equals("forfeit") && method.equals("POST")) {
                matches.forfeit(id, player(x).id());
                http.send(x, 200, Map.of());
                return;
            }
        }
        throw missing();
    }

    private PlayerIdentity player(HttpExchange x) {
        var p = identities.authenticate(guard.bearer(x)).orElseThrow(RequestGuard::unauthorized);
        guard.principal(p.id());
        return p;
    }

    private void protectReconSnapshot(HttpExchange exchange) {
        if (config.live().reconEnabled()) {
            requireAdmin(exchange);
        }
    }

    private void requireAdmin(HttpExchange exchange) {
        if (!identities.isAdmin(guard.bearer(exchange))) {
            throw new ApplicationFailure(
                    403,
                    "RECON_PRIVATE",
                    "В режиме разведки полный снимок и повтор доступны преподавателю. Клиент получает личное наблюдение.");
        }
        guard.principal("admin");
    }

    private void admin(HttpExchange x, String action, String method) throws IOException {
        if (!identities.isAdmin(guard.bearer(x))) {
            throw new ApplicationFailure(
                    401,
                    "ADMIN_REQUIRED",
                    "Ключ преподавателя не подходит. Откройте admin-key.txt именно запущенного сервера: полный путь указан в консоли ServerMain. Общий пароль игроков здесь не используется.");
        }
        guard.principal("admin");
        if (action.equals("recon/round") && method.equals("GET")) {
            http.send(x, 200, matches.reconRoundStatus());
            return;
        }
        if (action.equals("recon/round") && method.equals("POST")) {
            var start = http.body(x, Messages.RoundStartRequest.class);
            http.send(x, 200, matches.startReconRound(start.requestId(), start.expectedRoundId()));
            return;
        }
        if (action.startsWith("recon/round/") && method.equals("GET")) {
            http.send(x, 200, matches.reconRoundStatus(action.substring("recon/round/".length())));
            return;
        }
        if (action.equals("recon/teams") && method.equals("POST")) {
            var assignment = http.body(x, Messages.TeamAssignment.class);
            matches.assignReconTeam(identities.player(assignment.playerId()), assignment.team());
            http.send(x, 200, Map.of());
            return;
        }
        if (action.equals("rules")) {
            if (method.equals("GET")) {
                http.send(x, 200, matches.rulesControl());
                return;
            }
            if (method.equals("POST")) {
                var update = http.body(x, RulesUpdate.class);
                http.send(
                        x, 200, matches.changeRules(update.expectedRevision(), update.settings()));
                return;
            }
        }
        if (action.equals("rules/reset") && method.equals("POST")) {
            var reset = http.body(x, RulesReset.class);
            http.send(x, 200, matches.resetRules(reset.expectedRevision()));
            return;
        }
        if (action.equals("access")) {
            if (method.equals("GET")) {
                http.send(x, 200, new ServerAccess(identities.passwordConfigured()));
                return;
            }
            if (method.equals("POST")) {
                identities.configurePassword(http.body(x, ServerPassword.class).password());
                http.send(x, 200, new ServerAccess(identities.passwordConfigured()));
                return;
            }
        }
        if (action.equals("players") && method.equals("GET")) {
            http.send(x, 200, matches.adminPlayers(identities.presences()));
            return;
        }
        if (action.equals("metrics") && method.equals("GET")) {
            var result = new LinkedHashMap<String, Object>(matches.metrics());
            result.put("httpRequests", guard.requests.sum());
            result.put("rateLimited", guard.limited.sum());
            result.put("trackedIps", guard.trackedIps());
            result.put("maxConnections", config.network().maxConnections());
            result.put("maxStepRate", config.game().maxStepRate());
            result.put(
                    "usedHeapBytes",
                    Runtime.getRuntime().totalMemory() - Runtime.getRuntime().freeMemory());
            http.send(x, 200, result);
            return;
        }
        var pieces = action.split("/");
        if (pieces.length == 3 && pieces[0].equals("players") && method.equals("POST")) {
            String id = pieces[1];
            switch (pieces[2]) {
                case "approve" -> identities.approve(id);
                case "revoke" -> matches.revoke(id, () -> identities.revoke(id));
                case "restore" -> matches.restore(id, () -> identities.restore(id));
                case "kick" -> matches.kick(id, () -> identities.player(id));
                case "queue" -> {
                    http.send(x, 200, matches.enqueueOnline(() -> identities.onlinePlayer(id)));
                    return;
                }
                default -> throw missing();
            }
            http.send(x, 200, Map.of());
            return;
        }
        if (action.equals("tournament")) {
            if (method.equals("GET")) {
                http.send(x, 200, matches.tournament());
                return;
            }
            if (method.equals("POST")) {
                var request = http.body(x, TournamentRequest.class);
                if (request.playerIds() == null
                        || request.playerIds().size() > config.capacity().tournamentPlayers()) {
                    throw new ApplicationFailure(
                            400, "INVALID_ROSTER", "Проверьте число участников.");
                }
                var all = identities.players();
                var selected = new ArrayList<PlayerIdentity>();
                for (var id : request.playerIds()) {
                    selected.add(
                            all.stream()
                                    .filter(p -> p.id().equals(id))
                                    .findFirst()
                                    .orElseThrow(
                                            () ->
                                                    new ApplicationFailure(
                                                            400,
                                                            "UNKNOWN_PLAYER",
                                                            "Участник не найден.")));
                }
                http.send(
                        x,
                        201,
                        matches.startTournament(
                                selected, request.map(), request.rounds(), request.seed()));
                return;
            }
        }
        if (action.equals("tournament/cancel") && method.equals("POST")) {
            matches.cancelTournament();
            http.send(x, 200, Map.of());
            return;
        }
        throw missing();
    }

    private static Map<String, String> query(HttpExchange x) {
        var result = new HashMap<String, String>();
        var query = x.getRequestURI().getRawQuery();
        if (query == null) {
            return result;
        }
        for (var pair : query.split("&")) {
            var kv = pair.split("=", 2);
            if (kv.length != 2) {
                throw new IllegalArgumentException("Invalid query");
            }
            var key = URLDecoder.decode(kv[0], StandardCharsets.UTF_8);
            var value = URLDecoder.decode(kv[1], StandardCharsets.UTF_8);
            if (!Set.of("from", "limit").contains(key) || result.putIfAbsent(key, value) != null) {
                throw new IllegalArgumentException("Unknown or duplicate query field");
            }
        }
        return result;
    }

    /** One bounded immutable frame cache; accepted commands are never in public views. */
    private synchronized byte[] snapshotBytes(MatchView view) throws IOException {
        if (snapshot == null || !snapshot.view().equals(view)) {
            snapshot = new SnapshotBytes(view, json.write(view));
        }
        return snapshot.bytes();
    }

    private void error(HttpExchange x, int status, String code, String message) {
        try {
            http.send(x, status, new Messages.Error(code, message));
        } catch (IOException ignored) {
            /* A disconnected client cannot receive an error. */
        }
    }

    private static ApplicationFailure missing() {
        return new ApplicationFailure(404, "NOT_FOUND", "Адрес не найден.");
    }
}
