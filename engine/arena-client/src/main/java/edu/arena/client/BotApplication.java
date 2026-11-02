package edu.arena.client;

import edu.arena.core.bot.Bot;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.PrintWriter;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.http.HttpClient;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.channels.OverlappingFileLockException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.HexFormat;
import java.util.Locale;
import java.util.Objects;
import java.util.UUID;
import java.util.function.Consumer;
import java.util.function.Supplier;

/** Starts a student bot directly from an IDE, including registration and admission. */
public final class BotApplication {
    private static final String DEFAULT_SERVER = "http://127.0.0.1:8080";

    private BotApplication() {}

    /**
     * Reads connection details from the IDE console. The factory must create a fresh bot for each
     * life. Credentials stay in the user's home directory and are scoped to one server origin.
     */
    public static void run(Supplier<Bot> bots) throws IOException, InterruptedException {
        execute(
                bots,
                new BufferedReader(new InputStreamReader(System.in, StandardCharsets.UTF_8)),
                new PrintWriter(System.out, true, StandardCharsets.UTF_8),
                Path.of(System.getProperty("user.home"), ".tank-arena", "players"));
    }

    static void execute(
            Supplier<Bot> bots, BufferedReader input, PrintWriter output, Path credentials)
            throws IOException, InterruptedException {
        Objects.requireNonNull(bots, "bots");
        connect(
                (client, progress) -> new BotRunner(client, bots, 10).runQueued(progress),
                input,
                output,
                credentials);
    }

    @FunctionalInterface
    interface SessionStarter {
        void run(ArenaClient client, Consumer<String> progress)
                throws IOException, InterruptedException;
    }

    static void connect(
            SessionStarter session, BufferedReader input, PrintWriter output, Path credentials)
            throws IOException, InterruptedException {
        connect(session, input, output, credentials, false);
    }

    static void connect(
            SessionStarter session,
            BufferedReader input,
            PrintWriter output,
            Path credentials,
            boolean personalViewer)
            throws IOException, InterruptedException {
        output.println("Tank Arena · Java 21");
        URI server =
                origin(
                        ask(
                                input,
                                output,
                                "Адрес сервера [" + DEFAULT_SERVER + "]: ",
                                DEFAULT_SERVER));
        boolean insecure = requiresLanPermission(server);
        if (insecure) {
            output.println(
                    "HTTP передаёт данные без шифрования. Продолжайте только в доверенной сети аудитории.");
            String answer =
                    ask(input, output, "Разрешить HTTP для этого подключения? Введите да: ", "нет");
            if (!answer.equalsIgnoreCase("да")) {
                throw new IOException(
                        "Подключение отменено. Для внешнего сервера используйте HTTPS.");
            }
        }

        Files.createDirectories(credentials);
        Path tokenFile = credentials.resolve(credentialName(server));
        Path lockFile = tokenFile.resolveSibling(tokenFile.getFileName() + ".lock");
        try (var channel =
                        FileChannel.open(
                                lockFile,
                                StandardOpenOption.CREATE,
                                StandardOpenOption.WRITE,
                                LinkOption.NOFOLLOW_LINKS);
                var sessionLock = acquire(channel);
                var http =
                        HttpClient.newBuilder()
                                .connectTimeout(Duration.ofSeconds(3))
                                .followRedirects(HttpClient.Redirect.NEVER)
                                .build()) {
            var anonymous = new ArenaClient(http, server, null, insecure);
            ProtocolCompatibility.require(anonymous.info());
            var client = identify(anonymous, http, server, insecure, tokenFile, input, output);
            output.println("Арена в браузере: " + server);
            if (personalViewer) {
                output.println(
                        "Личный обзор: выберите на странице файл ключа "
                                + tokenFile.toAbsolutePath().normalize());
            }
            output.println("Ожидаем допуск и место на поле. Для остановки нажмите Stop в IDE.");
            ArenaAdmission.enqueue(
                    client,
                    "JOIN",
                    "",
                    "commons",
                    output::println,
                    personalViewer ? () -> ReconPreparation.await(client, output::println) : null);
            session.run(client, output::println);
        }
    }

    private static ArenaClient identify(
            ArenaClient anonymous,
            HttpClient http,
            URI server,
            boolean insecure,
            Path tokenFile,
            BufferedReader input,
            PrintWriter output)
            throws IOException, InterruptedException {
        if (Files.exists(tokenFile, LinkOption.NOFOLLOW_LINKS)) {
            try {
                return reconnect(http, server, insecure, tokenFile, input, output);
            } catch (TokenFiles.InvalidTokenFileException invalid) {
                recoverCredential(tokenFile, input, output, "Сохранённый ключ повреждён.");
            } catch (ArenaException rejected) {
                if (rejected.code().equals("REVOKED") || rejected.code().equals("BANNED")) {
                    throw new ArenaException(
                            rejected.status(),
                            rejected.code(),
                            "Доступ закрыт преподавателем. Попросите восстановить прежний аккаунт и запустите Main снова. Ключ сохранён.",
                            rejected.retryAfterSeconds());
                }
                if (rejected.status() != 401
                        || !(rejected.code().equals("UNAUTHORIZED")
                                || rejected.code().equals("AUTH_INVALID"))) {
                    throw rejected;
                }
                recoverCredential(
                        tokenFile,
                        input,
                        output,
                        "Сервер больше не узнаёт сохранённый ключ. Возможно, данные сервера были сброшены.");
            }
        }

        String name = ask(input, output, "Имя танка: ", "");
        if (name.isBlank()) {
            throw new IllegalArgumentException("Укажите имя танка и запустите Main снова.");
        }
        output.println("Ключ admin-key.txt нужен только преподавателю для входа в админку.");
        String password = ask(input, output, "Пароль сервера: ", "");
        var enrolled = enrollWithRateLimitRetry(anonymous, password, name, output);
        try {
            TokenFiles.writeNew(tokenFile, enrolled.token());
        } catch (IOException failure) {
            throw new IOException(
                    "Участник создан, но личный ключ не удалось сохранить. Попросите преподавателя отозвать эту регистрацию; затем устраните проблему записи и подключитесь заново.",
                    failure);
        }
        output.println(
                "Участник зарегистрирован: "
                        + enrolled.name()
                        + ". При следующем запуске пароль сервера не понадобится.");
        return new ArenaClient(http, server, enrolled.token(), insecure);
    }

    /** Retry only a definite rate-limit rejection, never a lost or ambiguous response. */
    private static edu.arena.protocol.Messages.Enrollment enrollWithRateLimitRetry(
            ArenaClient client, String password, String name, PrintWriter output)
            throws IOException, InterruptedException {
        long deadline = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(60);
        for (int attempt = 0; ; attempt++) {
            try {
                return client.enroll(password, name);
            } catch (ArenaException failure) {
                if (failure.status() != 429) {
                    throw failure;
                }
                long delay =
                        Math.max(1000L, failure.retryAfterSeconds() * 1000L)
                                + java.util.concurrent.ThreadLocalRandom.current().nextLong(1001);
                if (attempt >= 44
                        || java.util.concurrent.TimeUnit.MILLISECONDS.toNanos(delay)
                                >= deadline - System.nanoTime()) {
                    throw new IOException(
                            "Сервер занят регистрацией. Попробуйте подключиться позже или обратитесь к преподавателю.",
                            failure);
                }
                if (attempt == 0) {
                    output.println("Ожидаем регистрации: сервер обслуживает других участников.");
                }
                Thread.sleep(delay);
            }
        }
    }

    private static ArenaClient reconnect(
            HttpClient http,
            URI server,
            boolean insecure,
            Path tokenFile,
            BufferedReader input,
            PrintWriter output)
            throws IOException, InterruptedException {
        var client = new ArenaClient(http, server, TokenFiles.read(tokenFile), insecure);
        var participant = client.me();
        String name =
                ask(input, output, "Имя танка [" + participant.name() + "]: ", participant.name());
        if (!name.equals(participant.name())) {
            client.rename(name);
        }
        output.println("Подключён участник: " + name + ". Накопленные очки сохранены.");
        return client;
    }

    private static void recoverCredential(
            Path tokenFile, BufferedReader input, PrintWriter output, String reason)
            throws IOException {
        output.println(reason);
        output.println(
                "Для нового аккаунта понадобится пароль сервера. Старые очки не переносятся; прежний ключ останется в резервной копии.");
        String answer = ask(input, output, "Начать новую регистрацию? Введите да: ", "нет");
        if (!answer.equalsIgnoreCase("да")) {
            throw new IOException("Восстановление отменено. Сохранённый ключ не изменён.");
        }
        Path backup =
                tokenFile.resolveSibling(tokenFile.getFileName() + ".backup-" + UUID.randomUUID());
        Files.move(tokenFile, backup);
    }

    private static FileLock acquire(FileChannel channel) throws IOException {
        try {
            FileLock lock = channel.tryLock();
            if (lock != null) {
                return lock;
            }
        } catch (OverlappingFileLockException busy) {
            // The other session may be another thread of this same IDE process.
        }
        throw new IOException(
                "Клиент для этого сервера уже запущен. Нажмите Stop в предыдущем Main перед новым запуском.");
    }

    private static String ask(
            BufferedReader input, PrintWriter output, String prompt, String fallback)
            throws IOException {
        output.print(prompt);
        output.flush();
        String answer = input.readLine();
        if (answer == null) {
            throw new IOException("Ввод закрыт. Запустите Main в IDE с доступной консолью ввода.");
        }
        return answer.isBlank() ? fallback : answer.strip();
    }

    private static URI origin(String value) {
        URI parsed;
        try {
            parsed = URI.create(value);
        } catch (IllegalArgumentException invalid) {
            throw new IllegalArgumentException(
                    "Нужен адрес вида http://127.0.0.1:8080 или https://arena.example.");
        }
        String scheme =
                parsed.getScheme() == null ? "" : parsed.getScheme().toLowerCase(Locale.ROOT);
        if (parsed.getHost() == null
                || !(scheme.equals("http") || scheme.equals("https"))
                || parsed.getRawUserInfo() != null
                || parsed.getRawQuery() != null
                || parsed.getRawFragment() != null
                || !(parsed.getRawPath().isEmpty() || parsed.getRawPath().equals("/"))
                || parsed.getPort() == 0
                || parsed.getPort() > 65535) {
            throw new IllegalArgumentException(
                    "Укажите только адрес сервера: протокол, имя или IP и порт. Без пароля, пути и параметров.");
        }
        int port = parsed.getPort();
        if (scheme.equals("http") && port == 80 || scheme.equals("https") && port == 443) {
            port = -1;
        }
        try {
            return new URI(
                    scheme,
                    null,
                    parsed.getHost().toLowerCase(Locale.ROOT),
                    port,
                    null,
                    null,
                    null);
        } catch (URISyntaxException invalid) {
            throw new IllegalArgumentException("Некорректный адрес сервера.", invalid);
        }
    }

    private static boolean requiresLanPermission(URI server) {
        String host = server.getHost();
        boolean loopback =
                host.equals("localhost")
                        || host.equals("127.0.0.1")
                        || host.equals("[::1]")
                        || host.equals("::1");
        return server.getScheme().equals("http") && !loopback;
    }

    private static String credentialName(URI server) {
        try {
            byte[] hash =
                    MessageDigest.getInstance("SHA-256")
                            .digest(server.toASCIIString().getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hash) + ".token";
        } catch (NoSuchAlgorithmException unavailable) {
            throw new IllegalStateException("Java runtime does not provide SHA-256", unavailable);
        }
    }
}
