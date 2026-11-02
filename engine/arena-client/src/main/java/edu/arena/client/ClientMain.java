package edu.arena.client;

import edu.arena.core.bot.Bot;
import edu.arena.core.bot.Observation;
import edu.arena.core.engine.ArenaEngine;
import edu.arena.core.model.ArenaMap;
import edu.arena.core.model.Control;
import edu.arena.core.model.GameRules;
import edu.arena.core.model.GameState;
import edu.arena.core.model.Outcome;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.function.Supplier;

/** Command line entry point; credentials are read from files or a hidden console prompt. */
public final class ClientMain {
    private ClientMain() {}

    public static void main(String[] args) {
        try {
            execute(args);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
        } catch (ArenaException error) {
            System.err.println(
                    "Ошибка сервера: "
                            + error.code()
                            + " (HTTP "
                            + error.status()
                            + "). "
                            + error.getMessage());
            System.exit(2);
        } catch (IllegalArgumentException error) {
            System.err.println(error.getMessage());
            System.exit(2);
        } catch (IOException error) {
            System.err.println("Ошибка: " + error.getMessage());
            System.exit(2);
        } catch (java.util.ServiceConfigurationError error) {
            System.err.println(
                    "Не удалось загрузить локальный BotProvider. Проверьте JAR и META-INF/services.");
            System.exit(2);
        }
    }

    static void execute(String[] args) throws IOException, InterruptedException {
        if (args.length == 0 || args[0].equals("help") || args[0].equals("--help")) {
            help();
            return;
        }
        String command = args[0];
        if (!Set.of("register", "join", "connect", "compare", "practice", "replay", "info")
                .contains(command)) {
            throw new IllegalArgumentException("Неизвестная команда. Запустите help.");
        }
        Map<String, String> options = options(Arrays.copyOfRange(args, 1, args.length));
        if (!options.getOrDefault("map", "commons").equals("commons")) {
            throw new IllegalArgumentException("Общая арена использует карту commons.");
        }
        // Registration and inspection must work before a student has compiled their JAR.
        Supplier<Bot> factory =
                Set.of("practice", "compare", "join", "connect").contains(command)
                        ? BotCatalog.factory(options.getOrDefault("bot", "my-bot"))
                        : null;
        if (command.equals("practice")) {
            var result =
                    LocalPractice.run(
                            factory,
                            BotCatalog.factory(options.getOrDefault("opponent", "strafe")),
                            Integer.parseInt(options.getOrDefault("players", "12")),
                            Integer.parseInt(options.getOrDefault("seconds", "60")),
                            Long.parseLong(options.getOrDefault("seed", "2026")));
            var yours = result.standings().get(result.candidateSlot());
            System.out.println(
                    "Локальная тренировка: укрытия и респавн, "
                            + result.ticks()
                            + " шагов, "
                            + result.standings().size()
                            + " участников.");
            System.out.println(
                    "Ваш бот — очки: " + yours.kills() + "; смерти: " + yours.deaths() + ".");
            System.out.println(
                    "Всего респавнов: "
                            + result.respawns()
                            + "; снарядов остановлено стенами: "
                            + result.blockedShots()
                            + ".");
            System.out.println(
                    "Физика 20 Гц, респавн 2 с, защита 1,5 с. Это локальный опыт без сетевых сроков; в рейтинг не входит.");
            return;
        }
        if (command.equals("compare")) {
            compare(
                    factory,
                    BotCatalog.factory(options.getOrDefault("opponent", "strafe")),
                    Integer.parseInt(options.getOrDefault("players", "8")),
                    Long.parseLong(options.getOrDefault("seed", "2026")));
            return;
        }
        URI server = URI.create(options.getOrDefault("server", "http://127.0.0.1:8080"));
        Path tokenFile = Path.of(options.getOrDefault("token-file", "arena-token.txt"));
        boolean insecure = options.containsKey("allow-insecure-lan");
        if (insecure) {
            System.out.println(
                    "Разрешён HTTP в доверенной учебной сети. Для внешнего сервера используйте HTTPS.");
        }
        String token =
                command.equals("register") || command.equals("info")
                        ? null
                        : TokenFiles.read(tokenFile);
        try (var http =
                HttpClient.newBuilder()
                        .connectTimeout(Duration.ofSeconds(3))
                        .followRedirects(HttpClient.Redirect.NEVER)
                        .build()) {
            var client = new ArenaClient(http, server, token, insecure);
            if (!command.equals("info")) {
                ProtocolCompatibility.require(client.info());
            }
            if ((command.equals("join") || command.equals("connect"))
                    && options.containsKey("name")) {
                var named = client.rename(required(options, "name"));
                System.out.println("Имя танка: " + named.name());
            }
            switch (command) {
                case "info" -> {
                    var info = client.info();
                    System.out.println(
                            "Протокол "
                                    + info.version()
                                    + "; физика "
                                    + info.tickRate()
                                    + " шагов/с; сервер не чаще "
                                    + info.maxStepRate()
                                    + " шагов/с; опрос "
                                    + info.playerPollMillis()
                                    + " мс.");
                }
                case "register" -> register(client, options, tokenFile);
                case "join" -> {
                    var reply =
                            ArenaAdmission.enqueue(
                                    client, "JOIN", "", "commons", System.out::println);
                    System.out.println(
                            reply.queued()
                                    ? "Вход принят: ждём появления на поле."
                                    : "Арена назначена.");
                    new BotRunner(client, factory, 10).runQueued(System.out::println);
                }
                case "connect" -> new BotRunner(client, factory, 10).run(true, System.out::println);
                case "replay" -> {
                    String match = required(options, "match");
                    var replay = client.replay(match, 0, 1);
                    System.out.println(
                            "Матч "
                                    + replay.match().id()
                                    + ": "
                                    + replay.match().state().outcome()
                                    + "; причина "
                                    + replay.match().finishedReason()
                                    + "; кадров "
                                    + replay.total()
                                    + ".");
                }
                default -> throw new IllegalStateException("Command not handled");
            }
        }
    }

    private static void register(ArenaClient client, Map<String, String> options, Path tokenFile)
            throws IOException, InterruptedException {
        Path target = tokenFile.toAbsolutePath().normalize();
        if (Files.exists(target, LinkOption.NOFOLLOW_LINKS)) {
            throw new IOException(
                    "Файл ключа уже существует; регистрация не отправлена. Выберите другой --token-file.");
        }
        if (!Files.isDirectory(target.getParent()) || !Files.isWritable(target.getParent())) {
            throw new IOException("Каталог ключа недоступен для записи");
        }
        String password;
        if (options.containsKey("password-file")) {
            password = readServerPassword(Path.of(options.get("password-file")));
        } else {
            var console = System.console();
            if (console == null) {
                throw new IllegalArgumentException(
                        "Для регистрации передайте --password-file или выполните команду в интерактивном терминале.");
            }
            char[] secret = console.readPassword("Пароль сервера: ");
            if (secret == null) {
                throw new IOException("Регистрация отменена");
            }
            password = new String(secret).strip();
            Arrays.fill(secret, '\0');
        }
        String name = required(options, "name");
        var enrolled = client.enroll(password, name);
        try {
            TokenFiles.writeNew(target, enrolled.token());
        } catch (IOException failure) {
            throw new IOException(
                    "Аккаунт создан, но личный ключ не удалось сохранить. Попросите преподавателя отозвать эту регистрацию; затем устраните проблему записи и подключитесь заново.",
                    failure);
        }
        System.out.println(
                "Участник зарегистрирован: "
                        + enrolled.name()
                        + ". Ключ сохранён в "
                        + target
                        + ". Не отправляйте этот файл другим. Дождитесь допуска преподавателя; join или connect можно запустить уже сейчас.");
    }

    private static String readServerPassword(Path file) throws IOException {
        byte[] bytes;
        try (var input = Files.newInputStream(file, LinkOption.NOFOLLOW_LINKS)) {
            bytes = input.readNBytes(4097);
        }
        if (bytes.length > 4096) {
            throw new IOException("Файл пароля сервера слишком большой; оставьте только пароль.");
        }
        String text = StandardCharsets.UTF_8.newDecoder().decode(ByteBuffer.wrap(bytes)).toString();
        return (text.startsWith("\uFEFF") ? text.substring(1) : text).strip();
    }

    record LocalResult(
            long seed, int slot, int rank, int points, int tick, List<Integer> spawnOrder) {}

    static List<LocalResult> compare(
            Supplier<Bot> candidate, Supplier<Bot> opponent, int players, long seed) {
        if (players < 2 || players > 64) {
            throw new IllegalArgumentException("--players: от 2 до 64.");
        }
        var results = new ArrayList<LocalResult>();
        for (int round = 0; round < 4; round++) {
            long roundSeed = seed + round;
            var order = new ArrayList<Integer>();
            for (int i = 0; i < players; i++) {
                order.add(i);
            }
            Collections.shuffle(order, new Random(roundSeed));
            int self = order.indexOf(0);
            var bots = new ArrayList<Bot>();
            for (int id : order) {
                bots.add((id == 0 ? candidate : opponent).get());
            }
            var rules = GameRules.standard(20, 60);
            var map = ArenaMap.commons(players);
            var engine = new ArenaEngine(rules, map);
            GameState state = engine.initialState();
            while (state.outcome() == Outcome.RUNNING) {
                var controls = new ArrayList<Control>();
                for (int slot = 0; slot < players; slot++) {
                    var view = new Observation(slot, state, map, rules);
                    controls.add(
                            view.me().health() > 0
                                    ? bots.get(slot).decide(view)
                                    : Control.idle(view.me().position()));
                }
                state = engine.step(state, controls);
            }
            var place =
                    engine.placements(state).stream()
                            .filter(p -> p.slot() == self)
                            .findFirst()
                            .orElseThrow();
            var result =
                    new LocalResult(
                            roundSeed,
                            self,
                            place.rank(),
                            place.points(),
                            state.tick(),
                            List.copyOf(order));
            results.add(result);
            System.out.println(
                    "Раунд "
                            + (round + 1)
                            + ": seed="
                            + roundSeed
                            + ", танк №"
                            + (self + 1)
                            + ", место="
                            + place.rank()
                            + "/"
                            + players
                            + ", очки="
                            + place.points()
                            + ", шаг="
                            + state.tick());
            System.out.println("Порядок стартов (ваш бот=0): " + order);
        }
        System.out.println(
                "Сумма очков: "
                        + results.stream().mapToInt(LocalResult::points).sum()
                        + ". Локальная серия из 4 общих боёв, 20 шагов/с, 60 игровых секунд. В зачёт не входит; сетевой повтор не сохраняется.");
        return List.copyOf(results);
    }

    private static Map<String, String> options(String[] args) {
        var result = new LinkedHashMap<String, String>();
        Set<String> allowed =
                Set.of(
                        "server",
                        "token-file",
                        "bot",
                        "map",
                        "opponent",
                        "players",
                        "seed",
                        "seconds",
                        "password-file",
                        "name",
                        "match",
                        "allow-insecure-lan");
        for (int i = 0; i < args.length; i++) {
            String arg = args[i];
            if (!arg.startsWith("--") || !allowed.contains(arg.substring(2))) {
                throw new IllegalArgumentException("Неизвестный параметр; см. help.");
            }
            String key = arg.substring(2);
            String value = "true";
            if (!key.equals("allow-insecure-lan")) {
                if (++i >= args.length || args[i].startsWith("--")) {
                    throw new IllegalArgumentException("У параметра --" + key + " нет значения.");
                }
                value = args[i];
            }
            if (result.put(key, value) != null) {
                throw new IllegalArgumentException("Параметр --" + key + " указан дважды.");
            }
        }
        return result;
    }

    private static String required(Map<String, String> options, String key) {
        String value = options.get(key);
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("Нужен параметр --" + key + ".");
        }
        return value;
    }

    private static void help() {
        System.out.println(
                """
                Tank Arena — Java-клиент (Java 21)
                register --server URL --name ИМЯ --password-file FILE --token-file FILE
                join     --server URL --token-file FILE --name ИМЯ --bot my-bot
                connect  --server URL --token-file FILE --name ИМЯ --bot my-bot
                practice --bot my-bot --opponent strafe --players 12 --seconds 60 --seed 2026
                compare  --bot my-bot --opponent strafe --players 8 --seed 2026
                replay   --server URL --token-file FILE --match ID
                info     --server URL
                Без --bot выбирается my-bot из студенческого JAR; готовая стратегия не подставляется.
                join входит на арену. В постоянном режиме после смерти танк возвращается; завершение — Ctrl+C.
                connect ждёт назначения преподавателя. В режиме раундов join играет один раунд.
                --name задаёт имя танка перед входом; без него сохраняется имя регистрации и накопленный счёт.
                practice проверяет укрытия и респавн без сервера; compare — прежние конечные раунды на открытом поле.
                В register пароль сервера можно ввести скрыто в консоли, опустив --password-file.
                Для доверенной учебной сети HTTP: --allow-insecure-lan. Для Интернета нужен HTTPS.
                Ключ участника передаётся только через --token-file; файл храните у себя.
                """);
    }
}
