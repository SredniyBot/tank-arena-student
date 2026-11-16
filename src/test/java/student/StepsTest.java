package student;

import colony.homework.StepChecks;
import java.util.stream.Stream;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.TestFactory;
import org.junit.jupiter.api.TestMethodOrder;

/**
 * Шаги домашнего задания на учебных картах: ▶ у шага — его пять карт, ▶ у класса — все шаги.
 * У красной карты в сообщении — ссылка на повтор. Учебные карты одни у всех; «Сдать» играет новые.
 */
@TestMethodOrder(MethodOrderer.MethodName.class)
class StepsTest {

    @TestFactory
    Stream<DynamicTest> шаг1_Ходка() {
        return StepChecks.check(1, MyColony::new);
    }

    @TestFactory
    Stream<DynamicTest> шаг2_Найм() {
        return StepChecks.check(2, MyColony::new);
    }

    @TestFactory
    Stream<DynamicTest> шаг3_Склад() {
        return StepChecks.check(3, MyColony::new);
    }

    @TestFactory
    Stream<DynamicTest> шаг4_Завалы() {
        return StepChecks.check(4, MyColony::new);
    }

    @TestFactory
    Stream<DynamicTest> шаг5_Прокачка() {
        return StepChecks.check(5, MyColony::new);
    }
}
