package student;

import static org.junit.jupiter.api.Assertions.assertEquals;

import colony.local.LocalGame;
import java.nio.file.Path;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class MyColonyTest {

    @Test
    @DisplayName("Колония играет 200 тактов без исключений и таймаутов")
    void playsWithoutErrors(@TempDir Path dir) {
        var result = LocalGame.builder()
                .me(MyColony::new)
                .headless(true)
                .ticks(200)
                .workDir(dir)
                .run();

        var mine = result.of(1);
        assertEquals(0, mine.exceptions(), "исключения в onTick");
        assertEquals(0, mine.timeouts(), "onTick дольше секунды");
    }
}
