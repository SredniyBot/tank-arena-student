import { teamOf } from "./team-roster.js";

/** Simulated time since the arena process started; a new round does not reset the clock. */
export function gameClock(tick, tickRate) {
  const seconds = Math.floor(Math.max(0, tick || 0) / (tickRate > 0 ? tickRate : 20));
  return `${String(Math.floor(seconds / 60)).padStart(2, "0")}:${String(seconds % 60).padStart(2, "0")}`;
}

/** Visible projectiles are grouped by their visible owner's slot; no target is inferred. */
export function projectileSnapshot(match) {
  const counts = new Map();
  let unknown = 0;
  for (const shot of match?.state?.projectiles || []) {
    const owner = match.state.tanks.find((tank) => tank.slot === shot.owner && tank.health > 0);
    const permitted =
      owner && (!match.live || match.players?.some((player) => player.slot === owner.slot));
    if (permitted) counts.set(owner.slot, (counts.get(owner.slot) || 0) + 1);
    else unknown++;
  }
  return {
    shooters: [...counts]
      .map(([slot, count]) => ({ slot, count }))
      .sort((a, b) => b.count - a.count || a.slot - b.slot),
    unknown,
  };
}

// Conservative visibility test: a wall intersecting the centre ray expanded by the
// whole zone radius may obscure some part of the zone. False unknown is safe.
function zoneMayBeOccluded(origin, centre, walls) {
  return walls.some((wall) => {
    let near = 0,
      far = 1;
    for (const [at, delta, low, high] of [
      [origin.x, centre.x - origin.x, wall.x - 100, wall.x + wall.width + 100],
      [origin.y, centre.y - origin.y, wall.y - 100, wall.y + wall.height + 100],
    ]) {
      if (Math.abs(delta) < 1e-10) {
        if (at < low || at > high) return false;
      } else {
        const a = (low - at) / delta,
          b = (high - at) / delta;
        near = Math.max(near, Math.min(a, b));
        far = Math.min(far, Math.max(a, b));
        if (near > far) return false;
      }
    }
    return true;
  });
}

/** Full-view control status; a partial view must never imply hidden enemies are absent. */
export function controlZoneSnapshot(match) {
  if (match?.map?.id !== "recon-control") return [];
  const teams = match.recon?.teams ?? 4;
  const teamSize = match.recon?.teamSize ?? 3;
  return (match.state?.delivery?.depots || [])
    .filter((depot) => depot.id >= teams)
    .map((depot) => {
      if (match.partial) {
        const observer = match.state?.tanks?.find((tank) => tank.slot === match.self);
        const origin = match.visibility?.origin;
        const fullyVisible =
          observer?.health > 0 &&
          origin &&
          Array.isArray(match.map.walls) &&
          !zoneMayBeOccluded(origin, depot.position, match.map.walls) &&
          Math.hypot(origin.x - depot.position.x, origin.y - depot.position.y) + 100 <=
            (match.recon?.visionRadius ?? 0);
        if (!fullyVisible) return { position: depot.position, status: "unknown", team: null };
      }
      const present = new Set(
        (match.state?.tanks || [])
          .filter(
            (tank) =>
              tank.health > 0 && (!match.live || match.players?.some((p) => p.slot === tank.slot)),
          )
          .filter(
            (tank) =>
              Math.hypot(tank.position.x - depot.position.x, tank.position.y - depot.position.y) <=
              100,
          )
          .map((tank) => teamOf(match, tank.slot)),
      );
      if (present.size === 0) return { position: depot.position, status: "empty", team: null };
      if (present.size > 1) return { position: depot.position, status: "contested", team: null };
      return {
        position: depot.position,
        status: "controlled",
        team: [...present][0],
      };
    });
}

/** Current known cargo state; distances are straight lines to the base boundary, not routes. */
export function cargoSnapshot(match) {
  const state = match?.state;
  const cargo = state?.delivery?.cargo || [];
  const depots = state?.delivery?.depots || [];
  const permitted = (slot) => !match?.live || match.players?.some((player) => player.slot === slot);
  const carriers = cargo.flatMap((item) => {
    if (item.carrierSlot < 0 || !permitted(item.carrierSlot)) return [];
    const tank = state.tanks.find((entry) => entry.slot === item.carrierSlot && entry.health > 0);
    const depot = depots.find((entry) => entry.id === item.destinationId);
    if (!tank || !depot) return [];
    return [
      {
        slot: tank.slot,
        team: teamOf(match, tank.slot),
        distance: Math.max(
          0,
          Math.round(
            Math.hypot(depot.position.x - tank.position.x, depot.position.y - tank.position.y) -
              (depot.radius || 0),
          ),
        ),
      },
    ];
  });
  carriers.sort((a, b) => a.distance - b.distance || a.slot - b.slot);
  return {
    carriers,
    free: cargo.filter(
      (item) => item.carrierSlot < 0 && (item.availableAtTick ?? 0) <= (state?.tick ?? 0),
    ).length,
  };
}

/** Changes in sampled carriers are observations, not a complete event history. */
export function observedCarriers(previous, current) {
  if (
    !previous ||
    !current ||
    previous.partial ||
    current.partial ||
    previous.id !== current.id ||
    previous.recon?.roundId !== current.recon?.roundId ||
    previous.state.tick >= current.state.tick
  )
    return [];
  const before = new Map(
    (previous.state.delivery?.cargo || []).map((item) => [item.id, item.carrierSlot]),
  );
  return (current.state.delivery?.cargo || []).flatMap((item) => {
    if (
      !before.has(item.id) ||
      before.get(item.id) === item.carrierSlot ||
      item.carrierSlot < 0 ||
      (current.live && !current.players?.some((player) => player.slot === item.carrierSlot))
    )
      return [];
    return [{ slot: item.carrierSlot, tick: current.state.tick }];
  });
}

/** Facts from the current authorised frame, not guesses about a bot's intention. */
export function focusFacts(match, slot) {
  const tanks = match?.state?.tanks || [];
  const permitted = (item) =>
    !match.live || match.players?.some((player) => player.slot === item.slot);
  const tank = tanks.find((item) => item.slot === slot && permitted(item));
  if (!tank) return null;
  const delivery = match.state.delivery;
  const cargo = delivery?.cargo?.find((item) => item.carrierSlot === slot);
  const teamSize = match.recon?.teamSize || 3;
  const opponentsNearby = tanks.filter(
    (other) =>
      other.health > 0 &&
      permitted(other) &&
      teamOf(match, other.slot) !== teamOf(match, slot) &&
      Math.hypot(other.position.x - tank.position.x, other.position.y - tank.position.y) <= 260,
  ).length;
  return {
    tank,
    carrying: Boolean(cargo),
    destination: cargo ? delivery.depots.find((depot) => depot.id === cargo.destinationId) : null,
    opponentsNearby,
  };
}

/** Camera selection considers only tanks that are present in the current permitted frame. */
export function chooseActionFocus(match) {
  const tanks = (match?.state?.tanks || []).filter(
    (tank) =>
      tank.health > 0 &&
      (!match.live || match.players?.some((player) => player.slot === tank.slot)),
  );
  if (!tanks.length) return null;
  const score = (tank) => {
    const facts = focusFacts(match, tank.slot);
    const shotsNearby = (match.state.projectiles || []).filter(
      (shot) =>
        Math.hypot(shot.position.x - tank.position.x, shot.position.y - tank.position.y) < 200,
    ).length;
    return (
      Math.min(4, facts.opponentsNearby) * 7 +
      (facts.carrying ? 5 : 0) +
      Math.min(3, shotsNearby) * 2
    );
  };
  return [...tanks].sort((a, b) => score(b) - score(a) || a.slot - b.slot)[0].slot;
}

/** Center automatic framing between the selected tank and its closest observed opponent. */
export function cameraTarget(match, model, slot, progress, automatic) {
  const selected = model?.tanks.find((tank) => tank.slot === slot);
  if (!selected || !focusFacts(match, slot)) return null;
  const positionAt = (tank) => {
    const from = tank.previous?.position || tank.position;
    return {
      x: from.x + (tank.position.x - from.x) * progress,
      y: from.y + (tank.position.y - from.y) * progress,
    };
  };
  const position = positionAt(selected);
  if (!automatic) return position;
  const teamSize = match.recon?.teamSize || 3;
  const visibleSlots = new Set(
    match.state.tanks
      .filter(
        (tank) =>
          tank.health > 0 &&
          (!match.live || match.players?.some((player) => player.slot === tank.slot)),
      )
      .map((tank) => tank.slot),
  );
  let closest = null;
  let closestDistance = 260;
  for (const other of model.tanks) {
    if (!visibleSlots.has(other.slot) || teamOf(match, other.slot) === teamOf(match, slot))
      continue;
    const otherPosition = positionAt(other);
    const distance = Math.hypot(otherPosition.x - position.x, otherPosition.y - position.y);
    if (distance <= closestDistance) {
      closest = otherPosition;
      closestDistance = distance;
    }
  }
  return closest ? { x: (position.x + closest.x) / 2, y: (position.y + closest.y) / 2 } : position;
}

/** Clamped world rectangle used for both the large view and its overview indicator. */
export function cameraViewport(width, height, center, zoom = 2.2) {
  if (!center || !(zoom > 1)) return { x: 0, y: 0, width, height };
  const viewWidth = width / zoom;
  const viewHeight = height / zoom;
  return {
    x: Math.max(0, Math.min(width - viewWidth, center.x - viewWidth / 2)),
    y: Math.max(0, Math.min(height - viewHeight, center.y - viewHeight / 2)),
    width: viewWidth,
    height: viewHeight,
  };
}

/** A bounded visual approximation using public geometry; never a source of game observations. */
export function visibilityPolygon(map, origin, radius) {
  if (!(radius > 0)) return Object.freeze([]);
  const angles = Array.from({ length: 96 }, (_, i) => -Math.PI + (i * Math.PI * 2) / 96);
  for (const wall of map.walls) {
    for (const x of [wall.x, wall.x + wall.width]) {
      for (const y of [wall.y, wall.y + wall.height]) {
        const angle = Math.atan2(y - origin.y, x - origin.x);
        angles.push(angle - 0.00001, angle, angle + 0.00001);
      }
    }
  }
  return Object.freeze(
    angles
      .sort((a, b) => a - b)
      .map((angle) => {
        const dx = Math.cos(angle),
          dy = Math.sin(angle);
        let distance = radius;
        if (dx > 1e-10) distance = Math.min(distance, (map.width - origin.x) / dx);
        else if (dx < -1e-10) distance = Math.min(distance, -origin.x / dx);
        if (dy > 1e-10) distance = Math.min(distance, (map.height - origin.y) / dy);
        else if (dy < -1e-10) distance = Math.min(distance, -origin.y / dy);
        for (const wall of map.walls) {
          let near = 0,
            far = distance;
          for (const [at, direction, low, high] of [
            [origin.x, dx, wall.x, wall.x + wall.width],
            [origin.y, dy, wall.y, wall.y + wall.height],
          ]) {
            if (Math.abs(direction) < 1e-10) {
              if (at < low || at > high) far = -1;
            } else {
              const first = (low - at) / direction,
                second = (high - at) / direction;
              near = Math.max(near, Math.min(first, second));
              far = Math.min(far, Math.max(first, second));
            }
          }
          if (near <= far && far >= 0) distance = Math.min(distance, near);
        }
        return Object.freeze({
          x: Math.max(0, Math.min(map.width, origin.x + dx * distance)),
          y: Math.max(0, Math.min(map.height, origin.y + dy * distance)),
        });
      }),
  );
}

/** Converts a real partial response into presentation data without inventing unseen actors. */
export function reconMatch(frame) {
  const o = frame.observation;
  const tanks = [o.me, ...o.visibleTanks];
  return {
    id: frame.matchId,
    status: frame.status,
    map: o.map,
    rules: o.rules,
    state: {
      tick: o.timing.observedTick,
      tanks,
      projectiles: o.projectiles,
      events: [],
      eliminations: [],
      outcome: "RUNNING",
      safeRadius: 2000,
      delivery: o.combat ? null : { depots: o.depots, cargo: o.cargo, events: [] },
      combat: o.combat ? { supplies: o.combat.visibleSupplies, scores: o.combat.scores } : null,
    },
    players: tanks.map(
      (t) =>
        frame.players?.find((p) => p.slot === t.slot) || {
          slot: t.slot,
          id: String(t.slot),
          name: t.slot === o.self ? "Мой танк" : `Танк ${t.slot + 1}`,
        },
    ),
    live: {
      players: [{ slot: o.self, playerId: String(o.self), life: o.life }],
      events: [],
    },
    recon: {
      roundId: o.roundId,
      teams: o.mode.teams,
      teamSize: o.mode.teamSize,
      teamBySlot: o.teamBySlot,
      visionRadius: o.mode.visionRadius,
      cargoSpeedFactor: o.mode.cargoSpeedFactor ?? 1,
      teamScores: o.teamScores,
    },
    partial: true,
    self: o.self,
    visibility: {
      origin: o.me.position,
      polygon: visibilityPolygon(o.map, o.me.position, o.me.health > 0 ? o.mode.visionRadius : 0),
    },
  };
}

export function replayMatch(record, index) {
  const frame = record.frames[index];
  const world = frame.arena;
  return {
    id: record.label || "experiment",
    status: "RUNNING",
    map: record.map,
    rules: record.rules,
    state: world,
    players: world.tanks.map((t) => ({
      slot: t.slot,
      id: String(t.slot),
      name: `${["А", "Б", "В", "Г"][teamOf({ recon: record.mode }, t.slot)]} · ${t.slot + 1}`,
    })),
    live: {
      players: world.tanks.map((t) => ({
        slot: t.slot,
        playerId: String(t.slot),
        life: frame.lives[t.slot],
      })),
      events: [],
    },
    recon: {
      ...record.mode,
      cargoSpeedFactor: record.mode.cargoSpeedFactor ?? 1,
      teamScores: frame.teamScores,
    },
  };
}
