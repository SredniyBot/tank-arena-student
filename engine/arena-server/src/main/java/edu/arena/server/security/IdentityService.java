package edu.arena.server.security;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.text.Normalizer;
import java.time.Clock;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.Semaphore;
import java.util.function.LongSupplier;

/**
 * Enrollment and revocation for a single server process. Mutations commit to disk before becoming
 * visible. The HTTP layer must separately authorize administrative methods; possession of this
 * object is trusted.
 */
public final class IdentityService {
    private final int maxPlayers;
    private final Clock clock;
    private final LongSupplier nanoClock;
    private static final long ONLINE_WINDOW_NANOS = Duration.ofSeconds(15).toNanos();

    private record Seen(long epochMillis, long nanos) {}

    public record Presence(PlayerIdentity player, long lastSeenEpochMillis, boolean online) {}

    private final Map<String, Seen> lastSeen = new LinkedHashMap<>();
    private final String adminKey;
    private final byte[] adminHash;
    private final IdentityStore store;
    private final ServerPasswordStore passwordStore;
    private final Semaphore passwordWork = new Semaphore(1);
    private ServerPasswordStore.PasswordHash serverPassword;
    private IdentityStore.State state;
    private Map<String, IdentityStore.StoredPlayer> playersByTokenHash;

    public IdentityService(Path directory, int maxPlayers, int maxInvites, Clock clock)
            throws IOException {
        this(directory, maxPlayers, maxInvites, clock, System::nanoTime);
    }

    IdentityService(
            Path directory, int maxPlayers, int maxInvites, Clock clock, LongSupplier nanoClock)
            throws IOException {
        Objects.requireNonNull(directory, "directory");
        this.clock = Objects.requireNonNull(clock, "clock");
        this.nanoClock = Objects.requireNonNull(nanoClock, "nanoClock");
        if (maxPlayers < 1 || maxPlayers > 10_000 || maxInvites < 1 || maxInvites > 10_000) {
            throw new IllegalArgumentException("Identity capacities must be between 1 and 10000");
        }
        this.maxPlayers = maxPlayers;
        Path root = directory.toAbsolutePath().normalize();
        Files.createDirectories(root);
        if (Files.isSymbolicLink(root)) {
            throw new IOException("Identity directory must not be a symbolic link");
        }
        Path adminFile = root.resolve("admin-key.txt");
        Path storeFile = root.resolve("identity-store.bin");
        // The invitation bound exists only to read legacy identity files; invitations are inactive.
        this.store = new IdentityStore(storeFile, maxPlayers, maxInvites);
        this.passwordStore = new ServerPasswordStore(root.resolve("server-password.bin"));
        this.serverPassword = passwordStore.read();
        if (Files.exists(storeFile)) {
            if (!Files.exists(adminFile)) {
                throw new IOException("Administrator key is missing for existing identity store");
            }
            this.state = store.read();
        } else {
            this.state = new IdentityStore.State(new LinkedHashMap<>(), new LinkedHashMap<>());
        }
        if (Files.exists(adminFile)) {
            String existing =
                    new String(
                                    ProtectedFiles.readBounded(adminFile, 128),
                                    StandardCharsets.US_ASCII)
                            .strip();
            if (!SecretMaterial.validToken(existing)) {
                throw new IOException("Invalid administrator key file");
            }
            ProtectedFiles.restrictToOwner(adminFile);
            this.adminKey = existing;
        } else {
            this.adminKey = SecretMaterial.token();
            ProtectedFiles.writeAtomically(
                    adminFile, (adminKey + "\n").getBytes(StandardCharsets.US_ASCII));
        }
        this.adminHash = SecretMaterial.digest(adminKey.getBytes(StandardCharsets.US_ASCII));
        if (!Files.exists(storeFile)) {
            store.write(state);
        }
        rebuildTokenIndex();
    }

    /** Only for local administrator setup. Never send this value to a browser or log. */
    public String adminKey() {
        return adminKey;
    }

    public boolean isAdmin(String bearer) {
        return SecretMaterial.validToken(bearer)
                && MessageDigest.isEqual(
                        adminHash,
                        SecretMaterial.digest(bearer.getBytes(StandardCharsets.US_ASCII)));
    }

    public synchronized boolean passwordConfigured() {
        return serverPassword != null;
    }

    /** Replaces the shared registration password without changing existing player credentials. */
    public void configurePassword(String value) {
        String password = validatedPassword(value, true);
        if (isAdmin(password)) {
            throw failure(
                    400,
                    "ADMIN_KEY_NOT_PASSWORD",
                    "Ключ преподавателя нельзя использовать как общий пароль. Задайте отдельный пароль для студентов.");
        }
        acquirePasswordWork();
        try {
            var replacement = ServerPasswordStore.hash(password);
            synchronized (this) {
                try {
                    passwordStore.write(replacement);
                } catch (IOException unavailable) {
                    throw failure(
                            503, "STORAGE_UNAVAILABLE", "Не удалось сохранить пароль сервера.");
                }
                serverPassword = replacement;
            }
        } finally {
            passwordWork.release();
        }
    }

    public Enrollment enroll(String value, String name) {
        String validatedName = validatedName(name);
        String password = validatedPassword(value, false);
        if (isAdmin(password)) {
            throw failure(
                    401,
                    "ADMIN_KEY_NOT_PASSWORD",
                    "Введён ключ преподавателя. Для подключения игрока нужен общий пароль сервера из раздела «Преподавателю».");
        }
        ServerPasswordStore.PasswordHash expected;
        synchronized (this) {
            expected = serverPassword;
            if (expected == null) {
                throw failure(
                        503,
                        "PASSWORD_NOT_CONFIGURED",
                        "Преподаватель ещё не задал общий пароль сервера.");
            }
        }
        acquirePasswordWork();
        try {
            if (!expected.matches(password)) {
                throw invalidPassword();
            }
            synchronized (this) {
                if (serverPassword != expected) {
                    throw failure(
                            409,
                            "SERVER_PASSWORD_CHANGED",
                            "Пароль сервера изменился во время подключения. Повторите вход с новым паролем.");
                }
                if (state.players().size() >= maxPlayers) {
                    throw failure(503, "CAPACITY_REACHED", "Player capacity reached");
                }
                String token;
                do {
                    token = SecretMaterial.token();
                } while (playersByTokenHash.containsKey(SecretMaterial.hash(token)));
                String id = UUID.randomUUID().toString();
                var player =
                        new IdentityStore.StoredPlayer(
                                id, validatedName, SecretMaterial.hash(token), false, false);
                var players = new LinkedHashMap<>(state.players());
                players.put(id, player);
                commit(new IdentityStore.State(players, state.invites()));
                markSeen(id);
                return new Enrollment(id, validatedName, token);
            }
        } finally {
            passwordWork.release();
        }
    }

    private void acquirePasswordWork() {
        if (!passwordWork.tryAcquire()) {
            throw failure(
                    429,
                    "RATE_LIMITED",
                    "Сервер проверяет другое подключение. Подождите секунду и повторите вход.");
        }
    }

    private static String validatedPassword(String value, boolean configuration) {
        String password = value == null ? "" : value.strip();
        boolean valid =
                password.length() >= 8
                        && password.length() <= 128
                        && password.codePoints()
                                .noneMatch(
                                        code ->
                                                Character.isISOControl(code)
                                                        || Character.getType(code)
                                                                == Character.FORMAT
                                                        || Character.getType(code)
                                                                == Character.SURROGATE);
        if (!valid) {
            if (configuration) {
                throw failure(
                        400,
                        "INVALID_PASSWORD",
                        "Пароль должен содержать от 8 до 128 символов без управляющих знаков. Пробелы по краям не учитываются.");
            }
            throw invalidPassword();
        }
        return password;
    }

    public Optional<PlayerIdentity> authenticate(String token) {
        if (!SecretMaterial.validToken(token)) {
            return Optional.empty();
        }
        String tokenHash = SecretMaterial.hash(token);
        synchronized (this) {
            var player = playersByTokenHash.get(tokenHash);
            if (player == null) {
                return Optional.empty();
            }
            if (player.revoked()) {
                throw failure(
                        403,
                        "REVOKED",
                        "Доступ заблокирован преподавателем. Сохраните свой токен: после восстановления он снова будет действовать.");
            }
            markSeen(player.id());
            return Optional.of(player.publicIdentity());
        }
    }

    public synchronized List<PlayerIdentity> players() {
        return state.players().values().stream()
                .map(IdentityStore.StoredPlayer::publicIdentity)
                .toList();
    }

    public synchronized List<Presence> presences() {
        long now = nanoClock.getAsLong();
        return state.players().values().stream()
                .map(
                        player -> {
                            var seen = lastSeen.get(player.id());
                            return new Presence(
                                    player.publicIdentity(),
                                    seen == null ? 0 : seen.epochMillis(),
                                    !player.revoked() && online(seen, now));
                        })
                .toList();
    }

    public synchronized PlayerIdentity player(String id) {
        return storedPlayer(id).publicIdentity();
    }

    /** Called under the coordinator lock so revocation cannot race an administrator enqueue. */
    public synchronized PlayerIdentity onlinePlayer(String id) {
        var player = storedPlayer(id);
        if (player.revoked()) {
            throw failure(403, "REVOKED", "Доступ участника отозван.");
        }
        if (!player.approved()) {
            throw approvalRequired();
        }
        if (!online(lastSeen.get(id), nanoClock.getAsLong())) {
            throw failure(
                    409,
                    "PLAYER_OFFLINE",
                    "Клиент участника не подключён. Попросите его открыть арену или запустить connect.");
        }
        return player.publicIdentity();
    }

    public synchronized PlayerIdentity rename(String id, String name) {
        var current = storedPlayer(id);
        if (current.revoked()) {
            throw failure(403, "REVOKED", "Доступ участника отозван.");
        }
        var validated = validatedName(name);
        if (current.name().equals(validated)) {
            return current.publicIdentity();
        }
        var replacement =
                new IdentityStore.StoredPlayer(
                        current.id(),
                        validated,
                        current.tokenHash(),
                        current.revoked(),
                        current.approved());
        var players = new LinkedHashMap<>(state.players());
        players.put(id, replacement);
        commit(new IdentityStore.State(players, state.invites()));
        return replacement.publicIdentity();
    }

    public synchronized void approve(String id) {
        var existing = storedPlayer(id);
        if (existing.revoked()) {
            throw failure(403, "REVOKED", "Сначала восстановите доступ участника.");
        }
        if (existing.approved()) {
            return;
        }
        replace(existing, false, true);
    }

    public synchronized void revoke(String id) {
        var existing = storedPlayer(id);
        if (existing.revoked()) {
            return;
        }
        replace(existing, true, existing.approved());
    }

    public synchronized void restore(String id) {
        var existing = storedPlayer(id);
        if (!existing.revoked()) {
            return;
        }
        replace(existing, false, existing.approved());
        // A restored account must make a fresh authenticated request before it is considered
        // online.
        lastSeen.remove(id);
    }

    private IdentityStore.StoredPlayer storedPlayer(String id) {
        var player = state.players().get(id);
        if (player == null) {
            throw failure(404, "PLAYER_NOT_FOUND", "Участник не найден.");
        }
        return player;
    }

    private void replace(IdentityStore.StoredPlayer player, boolean revoked, boolean approved) {
        var players = new LinkedHashMap<>(state.players());
        players.put(
                player.id(),
                new IdentityStore.StoredPlayer(
                        player.id(), player.name(), player.tokenHash(), revoked, approved));
        commit(new IdentityStore.State(players, state.invites()));
    }

    private void markSeen(String id) {
        var previous = lastSeen.get(id);
        long millis = Math.max(0, clock.millis());
        lastSeen.put(
                id,
                new Seen(
                        previous == null ? millis : Math.max(previous.epochMillis(), millis),
                        nanoClock.getAsLong()));
    }

    private static boolean online(Seen seen, long now) {
        return seen != null && now - seen.nanos() >= 0 && now - seen.nanos() <= ONLINE_WINDOW_NANOS;
    }

    public static SecurityFailure approvalRequired() {
        return failure(
                403, "APPROVAL_REQUIRED", "Регистрация принята. Дождитесь допуска преподавателя.");
    }

    public static String validatedName(String name) {
        if (name == null || name.length() > 128) {
            throw invalidName();
        }
        String normalized = Normalizer.normalize(name.strip(), Normalizer.Form.NFC);
        int count = normalized.codePointCount(0, normalized.length());
        if (count < 1 || count > 32) {
            throw invalidName();
        }
        for (int offset = 0; offset < normalized.length(); ) {
            int cp = normalized.codePointAt(offset);
            int type = Character.getType(cp);
            if (!(Character.isLetterOrDigit(cp)
                    || type == Character.NON_SPACING_MARK
                    || type == Character.COMBINING_SPACING_MARK
                    || cp == ' '
                    || cp == '-'
                    || cp == '_'
                    || cp == '.')) {
                throw invalidName();
            }
            offset += Character.charCount(cp);
        }
        if (!normalized.codePoints().anyMatch(Character::isLetterOrDigit)) {
            throw invalidName();
        }
        return normalized;
    }

    private void commit(IdentityStore.State replacement) {
        try {
            store.write(replacement);
        } catch (IOException unavailable) {
            throw failure(503, "STORAGE_UNAVAILABLE", "Identity storage is unavailable");
        }
        state = replacement;
        rebuildTokenIndex();
    }

    private void rebuildTokenIndex() {
        var index = new LinkedHashMap<String, IdentityStore.StoredPlayer>();
        state.players().values().forEach(player -> index.put(player.tokenHash(), player));
        playersByTokenHash = index;
    }

    private static SecurityFailure invalidPassword() {
        return failure(
                401,
                "INVALID_SERVER_PASSWORD",
                "Пароль сервера не подходит. Уточните общий пароль у преподавателя и проверьте адрес сервера.");
    }

    private static SecurityFailure invalidName() {
        return failure(
                400,
                "INVALID_NAME",
                "Use 1–32 letters, numbers, spaces, periods, hyphens or underscores");
    }

    private static SecurityFailure failure(int status, String code, String message) {
        return new SecurityFailure(status, code, message);
    }
}
