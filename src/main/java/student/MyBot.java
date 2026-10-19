package student;

import edu.arena.core.bot.Bot;
import edu.arena.core.bot.Observation;
import edu.arena.core.model.Control;

/** Write your decisions here. Split the behavior into your own components when you need them. */
public final class MyBot implements Bot {
    @Override
    public Control decide(Observation view) {
        // TODO: implement your bot.
        return Control.idle(view.me().position());
    }
}
