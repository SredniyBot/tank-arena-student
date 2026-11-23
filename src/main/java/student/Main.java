package student;

import colony.local.Launcher;

/** Запуск игры: конфигурации «Играть», «Сдать» и «Самопроверка». Все аргументы — {@code --help}. */
public class Main {

    public static void main(String[] args) {
        Launcher.main(args, MyColony::new);
    }
}
