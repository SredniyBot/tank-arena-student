package student;

import colony.api.Base;
import colony.api.Colony;
import colony.api.ColonyView;
import colony.api.Deposit;
import colony.api.Direction;
import colony.api.Orders;
import colony.api.Pos;
import colony.api.Unit;
import colony.api.UnitType;
import colony.sdk.Pathfinder;

// Простая колония: рабочие идут к ближайшей жиле, несут энергию на базу, база нанимает. До цели уровня 1 она
// не дотягивает. Можно скопировать её в MyColony и дописать.
// Что нужно помнить между тактами, храни в полях объекта, не в static: static-поля переходят в следующий матч.
public class SimpleColony implements Colony {

    private final Pathfinder paths = Pathfinder.SHORTEST;

    @Override
    public void onTick(ColonyView view, Orders orders) {
        Base base = view.myBases().get(0);
        for (Unit worker : view.myUnits()) {
            if (worker.carry() >= view.rules().workerCarry()) {
                // груз полный — несём на базу
                if (worker.pos().manhattan(base.pos()) == 1) {
                    orders.transfer(worker.id(), directionTo(worker.pos(), base.pos()), worker.carry());
                } else {
                    paths.next(view, worker.pos(), base.pos()).ifPresent(d -> orders.move(worker.id(), d));
                }
                continue;
            }
            Deposit nearest = nearestDeposit(view, worker.pos());
            if (nearest == null) {
                continue;
            }
            if (worker.pos().manhattan(nearest.pos()) == 1) {
                orders.harvest(worker.id(), directionTo(worker.pos(), nearest.pos()));
            } else {
                paths.next(view, worker.pos(), nearest.pos()).ifPresent(d -> orders.move(worker.id(), d));
            }
        }
        // хватает энергии — нанимаем ещё рабочего
        if (view.energy() >= view.rules().workerCost() && view.myUnits().size() < view.rules().maxWorkers()) {
            orders.produce(base.id(), UnitType.WORKER);
        }
    }

    // ближайшая жила, в которой есть энергия (запас −1 — жилу ещё не видели)
    private Deposit nearestDeposit(ColonyView view, Pos from) {
        Deposit best = null;
        for (Deposit d : view.deposits()) {
            if (d.amount() != 0 && (best == null || from.manhattan(d.pos()) < from.manhattan(best.pos()))) {
                best = d;
            }
        }
        return best;
    }

    private Direction directionTo(Pos from, Pos to) {
        for (Direction d : Direction.values()) {
            if (from.step(d).equals(to)) {
                return d;
            }
        }
        return Direction.NORTH;
    }
}
