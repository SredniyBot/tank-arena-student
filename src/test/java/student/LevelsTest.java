package student;

import colony.homework.StepChecks;
import java.util.stream.Stream;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.TestFactory;
import org.junit.jupiter.api.TestMethodOrder;

/**
 * Уровни домашнего задания на учебных картах: ▶ у уровня — его пять карт и итог, ▶ у класса — все уровни.
 * Уровень готов к «Сдать», когда итог зелёный: в среднем на карту сдано не меньше цели уровня. У каждой карты есть
 * ссылка на повтор. Учебные карты одни у всех, «Сдать» играет новые.
 */
@TestMethodOrder(MethodOrderer.MethodName.class)
class LevelsTest {

    @TestFactory
    Stream<DynamicTest> уровень1() {
        return StepChecks.check(1, MyColony::new);
    }

    @TestFactory
    Stream<DynamicTest> уровень2() {
        return StepChecks.check(2, MyColony::new);
    }

    @TestFactory
    Stream<DynamicTest> уровень3() {
        return StepChecks.check(3, MyColony::new);
    }

    @TestFactory
    Stream<DynamicTest> уровень4() {
        return StepChecks.check(4, MyColony::new);
    }

    @TestFactory
    Stream<DynamicTest> уровень5() {
        return StepChecks.check(5, MyColony::new);
    }
}
