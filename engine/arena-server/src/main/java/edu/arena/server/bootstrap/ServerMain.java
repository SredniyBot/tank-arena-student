package edu.arena.server.bootstrap;

import edu.arena.server.config.ServerConfig;
import java.nio.file.Path;
import java.util.concurrent.CountDownLatch;

public final class ServerMain {
    private ServerMain() {}

    public static void main(String[] args) throws Exception {
        if (args.length > 1) {
            System.err.println("Usage: java -jar arena-server.jar [config/combat.properties]");
            System.exit(2);
            return;
        }
        var config =
                ServerConfig.load(Path.of(args.length == 1 ? args[0] : "config/combat.properties"));
        var server = new ArenaServer(config);
        Runtime.getRuntime().addShutdownHook(new Thread(server::close, "arena-shutdown"));
        server.start();
        System.out.println("Tank Arena: http://" + config.network().host() + ":" + server.port());
        System.out.println(
                "Файл ключа преподавателя: "
                        + config.dataDirectory()
                                .resolve("identity/admin-key.txt")
                                .toAbsolutePath()
                                .normalize());
        System.out.println(
                "Этот ключ вводится только в браузере: «Преподавателю». Задайте там общий пароль сервера и сообщите его студентам для подключения из "
                        + (config.live().combatEnabled()
                                ? "student.Main."
                                : config.live().reconEnabled()
                                        ? "student.ReconMain."
                                        : "student.Main."));
        if (config.live().reconEnabled()) {
            System.out.println(
                    config.live().combatEnabled()
                            ? "Самостоятельные участники: /combat.html; личный обзор, расходники и личный счёт."
                            : "Разведка и доставка: /recon.html; четыре команды по три места.");
        }
        System.out.println(
                "Profile: "
                        + "one shared arena, up to "
                        + config.capacity().participants()
                        + " tanks; simulation "
                        + config.game().simulationHz()
                        + " Hz; wall-clock limit "
                        + config.game().maxStepRate()
                        + " steps/s.");
        new CountDownLatch(1).await();
    }
}
