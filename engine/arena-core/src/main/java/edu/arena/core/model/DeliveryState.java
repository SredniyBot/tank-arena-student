package edu.arena.core.model;

import java.util.HashSet;
import java.util.List;
import java.util.Objects;

/** Immutable visible objective state. A null GameState.delivery keeps the legacy combat mode. */
public record DeliveryState(
        List<DeliveryDepot> depots, List<Cargo> cargo, List<DeliveryEvent> events) {
    public DeliveryState {
        depots = List.copyOf(Objects.requireNonNull(depots, "depots"));
        cargo = List.copyOf(Objects.requireNonNull(cargo, "cargo"));
        events = List.copyOf(Objects.requireNonNull(events, "events"));
        if (depots.size() < 2 || depots.size() > 64 || cargo.size() > 256 || events.size() > 512) {
            throw new IllegalArgumentException("Invalid objective size");
        }
        var depotIds = new HashSet<Integer>();
        for (DeliveryDepot depot : depots) {
            if (!depotIds.add(depot.id())) {
                throw new IllegalArgumentException("Duplicate delivery depot");
            }
        }
        var ids = new HashSet<Integer>();
        var carriers = new HashSet<Integer>();
        for (Cargo item : cargo) {
            if (!ids.add(item.id())
                    || !depotIds.contains(item.sourceId())
                    || !depotIds.contains(item.destinationId())
                    || (item.carrierSlot() >= 0 && !carriers.add(item.carrierSlot()))) {
                throw new IllegalArgumentException("Inconsistent cargo state");
            }
        }
        for (DeliveryEvent event : events) {
            if (!ids.contains(event.cargoId())) {
                throw new IllegalArgumentException("Unknown cargo in event");
            }
        }
    }
}
