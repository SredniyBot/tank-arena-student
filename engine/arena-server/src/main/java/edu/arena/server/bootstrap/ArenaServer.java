package edu.arena.server.bootstrap;

import com.sun.net.httpserver.HttpServer;
import edu.arena.core.model.Control;
import edu.arena.core.model.Vec2;
import edu.arena.protocol.JsonCodec;
import edu.arena.protocol.Messages.Command;
import edu.arena.protocol.Messages.CommandReceipt;
import edu.arena.server.config.ServerConfig;
import edu.arena.server.http.ArenaHttpHandler;
import edu.arena.server.match.MatchCoordinator;
import edu.arena.server.persistence.FileMatchRepository;
import edu.arena.server.security.IdentityService;
import edu.arena.server.tournament.TournamentService;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.channels.OverlappingFileLockException;
import java.nio.file.Files;
import java.nio.file.StandardOpenOption;
import java.time.Clock;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

/** Composition root: owns network, process lock and application-service lifetimes. */
public final class ArenaServer implements AutoCloseable {
    private final HttpServer server;
    private final ThreadPoolExecutor executor;
    private final MatchCoordinator matches;
    private final FileChannel lockChannel;
    private final FileLock lock;
    private volatile boolean closed;

    public ArenaServer(ServerConfig config) throws IOException {
        Files.createDirectories(config.dataDirectory());
        lockChannel =
                FileChannel.open(
                        config.dataDirectory().resolve("server.lock"),
                        StandardOpenOption.CREATE,
                        StandardOpenOption.WRITE);
        FileLock acquired;
        try {
            acquired = lockChannel.tryLock();
        } catch (OverlappingFileLockException e) {
            lockChannel.close();
            throw new IOException("Data directory is already in use", e);
        }
        if (acquired == null) {
            lockChannel.close();
            throw new IOException("Data directory is already in use");
        }
        lock = acquired;
        HttpServer created = null;
        ThreadPoolExecutor pool = null;
        MatchCoordinator coordinator = null;
        try {
            var network = config.network();
            configureTransport(network);
            var json = new JsonCodec();
            if (config.live().reconEnabled()) {
                prepareCommandCodec(json);
            }
            var identities =
                    new IdentityService(
                            config.dataDirectory().resolve("identity"),
                            config.capacity().players(),
                            config.capacity().invites(),
                            Clock.systemUTC());
            var repository =
                    new FileMatchRepository(
                            config.dataDirectory().resolve("matches"),
                            config.capacity().storedMatches(),
                            config.capacity().historyBytes(),
                            json);
            var tournaments =
                    new TournamentService(
                            config.dataDirectory().resolve("tournament.json"), json, repository);
            coordinator =
                    new MatchCoordinator(
                            config, repository, tournaments, Clock.systemUTC(), System::nanoTime);
            created =
                    HttpServer.create(
                            new InetSocketAddress(network.host(), network.port()),
                            network.requestQueue());
            pool =
                    new ThreadPoolExecutor(
                            network.workers(),
                            network.workers(),
                            0,
                            TimeUnit.SECONDS,
                            new ArrayBlockingQueue<>(network.requestQueue()),
                            Thread.ofPlatform().name("arena-http-", 0).factory(),
                            new ThreadPoolExecutor.AbortPolicy());
            created.setExecutor(pool);
            created.createContext("/", new ArenaHttpHandler(config, identities, coordinator, json));
            server = created;
            executor = pool;
            matches = coordinator;
        } catch (IOException | RuntimeException e) {
            if (created != null) {
                created.stop(0);
            }
            if (pool != null) {
                pool.shutdownNow();
            }
            if (coordinator != null) {
                coordinator.close();
            }
            lock.release();
            lockChannel.close();
            throw e;
        }
    }

    public void start() {
        server.start();
        matches.start();
    }

    public int port() {
        return server.getAddress().getPort();
    }

    private static void configureTransport(ServerConfig.Network n) {
        System.setProperty("jdk.httpserver.maxConnections", Integer.toString(n.maxConnections()));
        System.setProperty(
                "sun.net.httpserver.maxIdleConnections",
                Integer.toString(Math.min(16, n.maxConnections())));
        System.setProperty("sun.net.httpserver.idleInterval", "10");
        System.setProperty("sun.net.httpserver.maxReqHeaders", "32");
        System.setProperty("sun.net.httpserver.maxReqHeaderSize", "8192");
        // OpenJDK/Axiom 21 implementation interprets these two properties in seconds.
        System.setProperty(
                "sun.net.httpserver.maxReqTime", Integer.toString(n.requestTimeoutSeconds()));
        System.setProperty(
                "sun.net.httpserver.maxRspTime", Integer.toString(n.requestTimeoutSeconds()));
        System.setProperty("sun.net.httpserver.drainAmount", "4096");
        System.setProperty("sun.net.httpserver.nodelay", "true");
    }

    private static void prepareCommandCodec(JsonCodec json) throws IOException {
        byte[] example = json.write(new Command(0, Control.idle(new Vec2(0, 0))));
        json.read(example, Command.class);
        json.write(new CommandReceipt(0, 2, 4, false));
    }

    @Override
    public synchronized void close() {
        if (closed) {
            return;
        }
        closed = true;
        server.stop(1);
        matches.close();
        executor.shutdownNow();
        try {
            lock.release();
            lockChannel.close();
        } catch (IOException e) {
            System.err.println("Could not release server lock cleanly.");
        }
    }
}
