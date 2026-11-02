package edu.arena.server.http;

import com.sun.net.httpserver.HttpExchange;
import edu.arena.protocol.Messages;
import edu.arena.server.config.ServerConfig;
import edu.arena.server.match.ApplicationFailure;
import edu.arena.server.security.BoundedRateLimiter;
import java.net.URI;
import java.time.Duration;
import java.util.concurrent.atomic.LongAdder;

/** Cheap admission checks run before body parsing or application work. */
final class RequestGuard {
    private final BoundedRateLimiter preparation;
    private final java.util.concurrent.Semaphore preparationPermits =
            new java.util.concurrent.Semaphore(4);
    private final BoundedRateLimiter global;
    private final BoundedRateLimiter ip;
    private final BoundedRateLimiter principal;
    private final BoundedRateLimiter enrollment;
    private final BoundedRateLimiter replay;
    private final BoundedRateLimiter globalReplay;
    final LongAdder requests = new LongAdder();
    final LongAdder limited = new LongAdder();

    RequestGuard(ServerConfig config) {
        this(config, System::nanoTime);
    }

    RequestGuard(ServerConfig config, java.util.function.LongSupplier nanoClock) {
        var n = config.network();
        var ttl = Duration.ofMinutes(5);
        int preparationRps = Math.max(1, Math.min(n.globalRps(), n.ipRps()) / 5);
        preparation = new BoundedRateLimiter(1, preparationRps, preparationRps * 2, ttl, nanoClock);
        global = new BoundedRateLimiter(1, n.globalRps(), n.globalRps() * 2, ttl, nanoClock);
        ip =
                new BoundedRateLimiter(
                        config.capacity().ipKeys(), n.ipRps(), n.ipRps() * 2, ttl, nanoClock);
        principal =
                new BoundedRateLimiter(
                        config.capacity().players() + 1,
                        n.playerRps(),
                        n.playerRps() * 2,
                        ttl,
                        nanoClock);
        enrollment =
                new BoundedRateLimiter(
                        config.capacity().ipKeys(),
                        n.enrollmentRps(),
                        n.enrollmentRps() * 3,
                        ttl,
                        nanoClock);
        replay =
                new BoundedRateLimiter(
                        config.capacity().ipKeys() + config.capacity().players() + 1,
                        n.replayRps(),
                        n.replayRps() * 2,
                        ttl,
                        nanoClock);
        globalReplay =
                new BoundedRateLimiter(
                        1, n.globalReplayRps(), n.globalReplayRps() * 2, ttl, nanoClock);
    }

    void before(HttpExchange x) {
        requests.increment();
        if ("/api/v2/recon/prepare".equals(x.getRequestURI().getRawPath())
                && !preparation.allow("all")) {
            reject();
        }
        if (!ip.allow(peer(x)) || !global.allow("all")) {
            reject();
        }
        if (x.getRequestURI().toString().length() > 2048) {
            throw new ApplicationFailure(414, "URI_TOO_LONG", "Адрес запроса слишком длинный.");
        }
        var origins = x.getRequestHeaders().get("Origin");
        if (origins != null) {
            try {
                if (origins.size() != 1) {
                    throw new IllegalArgumentException();
                }
                var origin = URI.create(origins.getFirst());
                var host = x.getRequestHeaders().getFirst("Host");
                if (!java.util.Set.of("http", "https").contains(origin.getScheme())
                        || origin.getRawAuthority() == null
                        || !origin.getRawAuthority().equalsIgnoreCase(host)) {
                    throw new IllegalArgumentException();
                }
            } catch (IllegalArgumentException e) {
                throw new ApplicationFailure(
                        403, "ORIGIN_REJECTED", "Этот источник запроса не разрешён.");
            }
        }
    }

    /** Only the public discovery endpoint may omit the protocol declaration. */
    void requireProtocol(HttpExchange exchange) {
        String path = exchange.getRequestURI().getRawPath();
        if (!path.startsWith("/api/")) {
            return;
        }
        var versions = exchange.getRequestHeaders().get(Messages.VERSION_HEADER);
        if (versions == null
                && path.equals("/api/v2/info")
                && exchange.getRequestMethod().equals("GET")) {
            return;
        }
        if (versions == null
                || versions.size() != 1
                || !Messages.VERSION.equals(versions.getFirst())) {
            throw new ApplicationFailure(
                    409,
                    "INCOMPATIBLE_PROTOCOL",
                    "Требуется протокол Tank Arena "
                            + Messages.VERSION
                            + ". Обновите SDK из GitHub или перезагрузите страницу.");
        }
    }

    void beginPreparation() {
        if (!preparationPermits.tryAcquire()) {
            throw new ApplicationFailure(
                    429, "PREPARATION_BUSY", "Сервер занят подготовкой. Повторите позже.");
        }
    }

    void endPreparation() {
        preparationPermits.release();
    }

    void principal(String id) {
        if (!principal.allow(id)) {
            reject();
        }
    }

    void enrollment(HttpExchange x) {
        if (!enrollment.allow(peer(x))) {
            reject();
        }
    }

    void replay(HttpExchange x, String identity) {
        if (!replay.allow(identity == null ? "ip:" + peer(x) : "p:" + identity)
                || !globalReplay.allow("all")) {
            reject();
        }
    }

    String bearer(HttpExchange x) {
        var values = x.getRequestHeaders().get("Authorization");
        if (values == null) {
            return "";
        }
        if (values.size() != 1) {
            throw unauthorized();
        }
        var value = values.getFirst();
        if (!value.startsWith("Bearer ") || value.length() > 160) {
            throw unauthorized();
        }
        return value.substring(7);
    }

    static ApplicationFailure unauthorized() {
        return new ApplicationFailure(
                401, "UNAUTHORIZED", "Войдите по действующему ключу участника.");
    }

    private String peer(HttpExchange x) {
        return x.getRemoteAddress().getAddress().getHostAddress();
    }

    private void reject() {
        limited.increment();
        throw new ApplicationFailure(
                429, "RATE_LIMITED", "Слишком много запросов. Подождите и повторите попытку.");
    }

    int trackedIps() {
        return ip.keyCount();
    }
}
