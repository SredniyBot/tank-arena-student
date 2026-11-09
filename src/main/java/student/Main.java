package student;

import colony.local.Launcher;

/** Запуск игры. Аргументы — в README, раздел «Аргументы». */
public class Main {

    public static void main(String[] args) {
        Launcher.main(args, MyColony::new);
    }
}
