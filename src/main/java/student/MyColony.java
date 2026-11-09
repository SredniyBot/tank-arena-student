package student;

import colony.api.Colony;
import colony.api.ColonyView;
import colony.api.Orders;

// Что нужно помнить между тактами, храни в полях объекта, не в static: static-поля переходят в следующий матч лестницы.
public class MyColony implements Colony {

    @Override
    public void onTick(ColonyView view, Orders orders) {
        // Вызывается каждый такт. view — что видит колония: юниты, база, жилы, запас энергии.
        // orders — приказы на этот такт: одному юниту — один приказ.
        // Начни с рабочего: view.myUnits(), затем orders.move(...) или orders.harvest(...).
    }
}
