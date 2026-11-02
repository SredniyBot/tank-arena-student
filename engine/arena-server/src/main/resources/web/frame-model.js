/** Presentation-only data. No transport, credentials, input ownership or game decisions. */
// The twelve classroom colours are curated for a pale concrete field; names also identify tanks.
const classroomColours = [
  "#268fbc",
  "#d98c2d",
  "#835dc0",
  "#279e90",
  "#cf5b71",
  "#5d8e39",
  "#e1693c",
  "#4675b4",
  "#b25c9b",
  "#b39a2a",
  "#37847e",
  "#a65c4a",
];
export const tankPalette = Object.freeze(
  Array.from({ length: 64 }, (_, slot) => {
    if (slot < classroomColours.length) return classroomColours[slot];
    const hue = (200 + slot * 137.508) % 360;
    const channel = (n) => {
      const k = (n + hue / 30) % 12;
      return Math.round(255 * (0.58 - 0.29 * Math.max(-1, Math.min(k - 3, 9 - k, 1))))
        .toString(16)
        .padStart(2, "0");
    };
    return "#" + channel(0) + channel(8) + channel(4);
  }),
);
const point = (value) => Object.freeze({ x: value.x, y: value.y });
const pose = (value) =>
  Object.freeze({
    position: point(value.position),
    heading: value.heading,
    gunHeading: value.gunHeading,
  });

/** Timers describe authoritative game ticks, never elapsed browser time. */
export function combatStatus(tank, rules = {}) {
  const rate = rules.tickRate || 20,
    alive = tank.health > 0;
  const reload = Math.max(0, tank.cooldownTicks || 0),
    shield = Math.max(0, tank.shieldTicks || 0);
  const recovery = Math.max(0, tank.shieldCooldownTicks || 0);
  // Intent is applied at the next boundary, after the engine decrements cooldowns.
  return Object.freeze({
    alive,
    fireReady: alive && reload <= 1 && shield === 0,
    shieldActive: alive && shield > 0,
    shieldReady: alive && shield === 0 && recovery <= 1,
    reloadSeconds: reload / rate,
    shieldSeconds: shield / rate,
    shieldCooldownSeconds: recovery / rate,
    reloadProgress: Math.max(0, Math.min(1, 1 - reload / (rules.reloadTicks || 1))),
    shieldProgress: Math.max(
      0,
      Math.min(
        1,
        shield > 0
          ? shield / (rules.shieldDurationTicks || 1)
          : 1 - recovery / (rules.shieldCooldownTicks || 1),
      ),
    ),
  });
}

/** Cumulative elimination data survives missed polls; current frame bounds prevent replay spoilers. */
export function eliminationFeed(match, frame, selectedSlot = -1) {
  const players = new Map((match?.players || []).map((player) => [player.slot, player]));
  const reasons = {
    DESTROYED: "Уничтожен",
    ZONE: "Зона",
    FORFEIT: "Покинул раунд",
    COMMAND_TIMEOUT: "Нет команд",
    REVOKED: "Доступ заблокирован",
    ADMIN_KICK: "Снят преподавателем",
    DELIVERED: "Доставил груз · +1",
  };
  const unique = new Map();
  if (match?.live) {
    const slots = new Map((match.players || []).map((player) => [player.id, player.slot]));
    for (const entry of match.live.events || []) {
      if (
        entry.tick > frame?.tick ||
        !["DESTROYED", "FORFEIT", "COMMAND_TIMEOUT", "REVOKED", "ADMIN_KICK", "DELIVERED"].includes(
          entry.reason,
        )
      )
        continue;
      const slot = slots.get(entry.victimId) ?? -1,
        actorSlot = slots.get(entry.actorId) ?? -1;
      const credited =
        entry.reason === "DESTROYED" && Boolean(entry.actorId) && entry.actorId !== entry.victimId;
      const key = `${match.id}:live:${entry.sequence}`;
      unique.set(
        key,
        Object.freeze({
          key,
          tick: entry.tick,
          slot,
          actorSlot: credited ? actorSlot : -1,
          name: entry.victimName || "Участник",
          actorName: credited ? entry.actorName || "Участник" : null,
          reason: reasons[entry.reason] || "Выбыл",
          selected:
            selectedSlot >= 0 &&
            (slot === selectedSlot || (credited && actorSlot === selectedSlot)),
        }),
      );
    }
    return Object.freeze([...unique.values()].sort((a, b) => b.tick - a.tick).slice(0, 8));
  }
  for (const entry of frame?.eliminations || []) {
    if (entry.tick > frame.tick) continue;
    const key = `${match?.id || "empty"}:${entry.tick}:${entry.slot}`;
    const actorSlot =
      entry.reason === "DESTROYED" &&
      Number.isInteger(entry.actorSlot) &&
      entry.actorSlot >= 0 &&
      entry.actorSlot !== entry.slot
        ? entry.actorSlot
        : -1;
    unique.set(
      key,
      Object.freeze({
        key,
        tick: entry.tick,
        slot: entry.slot,
        actorSlot,
        name: players.get(entry.slot)?.name || `Танк №${entry.slot + 1}`,
        actorName: actorSlot >= 0 ? players.get(actorSlot)?.name || `Танк №${actorSlot + 1}` : null,
        reason: reasons[entry.reason] || "Выбыл",
        selected: selectedSlot >= 0 && (entry.slot === selectedSlot || actorSlot === selectedSlot),
      }),
    );
  }
  return Object.freeze(
    [...unique.values()].sort((a, b) => b.tick - a.tick || a.slot - b.slot).slice(0, 8),
  );
}

/** Copy only the fields used by views. Mutating a view can never mutate a network snapshot. */
export function createFrameModel({
  match,
  frame,
  previousFrame = null,
  previousLive = null,
  nextFrame = null,
  selfId = null,
  focusSlot = -1,
  aim = null,
  mode = "watch",
  controlZones = [],
}) {
  const players = new Map((match?.players || []).map((player) => [player.slot, player]));
  const nextTanks = new Map((nextFrame?.tanks || []).map((tank) => [tank.slot, tank]));
  const nextProjectiles = new Map(
    (nextFrame?.projectiles || []).map((projectile) => [projectile.id, projectile]),
  );
  const beforeTanks = new Map((previousFrame?.tanks || []).map((tank) => [tank.slot, tank]));
  const beforeProjectiles = new Map(
    (previousFrame?.projectiles || []).map((projectile) => [projectile.id, projectile]),
  );
  const continuous = Boolean(match?.live);
  const colourFor = (slot) => tankPalette[teamOf(match, slot)] || tankPalette[0];
  const currentLives = new Map((match?.live?.players || []).map((player) => [player.slot, player]));
  const beforeLives = new Map((previousLive?.players || []).map((player) => [player.slot, player]));
  const sameLife = (slot) =>
    !previousLive ||
    (currentLives.get(slot)?.playerId === beforeLives.get(slot)?.playerId &&
      currentLives.get(slot)?.life === beforeLives.get(slot)?.life);
  // A revived tank appears at its new location; it must not fly across the map.
  const continuousPose = (tank, candidate) =>
    candidate &&
    candidate.health > 0 &&
    tank.health > 0 &&
    Math.hypot(candidate.position.x - tank.position.x, candidate.position.y - tank.position.y) <
      (match?.rules?.tankRadius || 18) * 6
      ? candidate
      : null;
  // The public feed can confirm a destruction missed between spectator polls. Infer only
  // its visual burst, at the observed corpse position, within the same player's life.
  // Initial frames, replay seeks and revived/reassigned slots must never invent an explosion.
  const visualEvents = [...(frame?.events || [])];
  if (
    continuous &&
    previousFrame &&
    previousLive &&
    !nextFrame &&
    mode !== "replay" &&
    previousFrame.tick < frame.tick
  ) {
    for (const tank of frame.tanks) {
      const before = beforeTanks.get(tank.slot),
        life = currentLives.get(tank.slot),
        oldLife = beforeLives.get(tank.slot);
      if (
        tank.health > 0 ||
        !before ||
        before.health <= 0 ||
        !life ||
        !oldLife ||
        life.playerId !== oldLife.playerId ||
        life.life !== oldLife.life ||
        visualEvents.some((event) => event.type === "DESTROYED" && event.target === tank.slot)
      )
        continue;
      const confirmed = match.live.events.find(
        (event) =>
          event.reason === "DESTROYED" &&
          event.victimId === life.playerId &&
          event.tick > previousFrame.tick &&
          event.tick <= frame.tick,
      );
      if (confirmed)
        visualEvents.push({
          type: "DESTROYED",
          target: tank.slot,
          actor: match.players.find((player) => player.id === confirmed.actorId)?.slot ?? -1,
          position: tank.position,
        });
    }
  }
  return Object.freeze({
    id: match?.id || "empty",
    controlMode: match?.map?.id === "recon-control",
    partial: Boolean(match?.partial),
    observerSlot: match?.self ?? -1,
    visionRadius: match?.recon?.visionRadius ?? 0,
    controlZones: Object.freeze(
      controlZones.map((zone) =>
        Object.freeze({
          position: point(zone.position),
          status: zone.status,
          team: zone.team,
        }),
      ),
    ),
    tick: frame?.tick ?? -1,
    hasFrame: Boolean(frame),
    width: match?.map?.width || 1280,
    height: match?.map?.height || 1280,
    tankRadius: match?.rules?.tankRadius || 18,
    hitPoints: match?.rules?.hitPoints || 3,
    bulletRadius: match?.rules?.bulletRadius || 3,
    safeRadius: nextFrame?.safeRadius ?? frame?.safeRadius ?? 610,
    previousSafeRadius: (nextFrame ? frame : previousFrame)?.safeRadius ?? frame?.safeRadius ?? 610,
    walls: Object.freeze(
      (match?.map?.walls || []).map((wall) =>
        Object.freeze({
          x: wall.x,
          y: wall.y,
          width: wall.width,
          height: wall.height,
        }),
      ),
    ),
    continuous,
    visibility:
      match?.partial && match.visibility
        ? Object.freeze({
            origin: point(match.visibility.origin),
            polygon: Object.freeze(match.visibility.polygon.map(point)),
          })
        : null,
    recon: match?.recon
      ? Object.freeze({
          teams: match.recon.teams,
          teamSize: match.recon.teamSize,
        })
      : null,
    supplies: frame?.combat?.supplies || [],
    delivery: frame?.delivery
      ? Object.freeze({
          depots: Object.freeze(
            frame.delivery.depots.map((depot) =>
              Object.freeze({
                id: depot.id,
                position: point(depot.position),
                radius: depot.radius,
              }),
            ),
          ),
          cargo: Object.freeze(
            frame.delivery.cargo.map((cargo) =>
              Object.freeze({
                id: cargo.id,
                sourceId: cargo.sourceId,
                destinationId: cargo.destinationId,
                position: point(cargo.position),
                carrierSlot: cargo.carrierSlot,
                available: cargo.availableAtTick <= frame.tick,
              }),
            ),
          ),
        })
      : null,
    tanks: Object.freeze(
      (frame?.tanks || [])
        .filter((tank) => !continuous || players.has(tank.slot))
        .map((tank) =>
          Object.freeze({
            slot: tank.slot,
            life: currentLives.get(tank.slot)?.life ?? 0,
            health: tank.health,
            cooldownTicks: tank.cooldownTicks || 0,
            shieldTicks: tank.shieldTicks || 0,
            shieldCooldownTicks: tank.shieldCooldownTicks || 0,
            ...pose(
              match?.partial
                ? tank
                : continuous
                  ? continuousPose(tank, nextTanks.get(tank.slot)) || tank
                  : nextTanks.get(tank.slot) || tank,
            ),
            // An observed opponent may have just left visibility; never extend its track
            // between snapshots. Only the observer's own, same-life movement is smoothed.
            previous:
              match?.partial && tank.slot !== match.self
                ? null
                : nextFrame
                  ? pose(tank)
                  : continuous
                    ? sameLife(tank.slot) && continuousPose(tank, beforeTanks.get(tank.slot))
                      ? pose(beforeTanks.get(tank.slot))
                      : null
                    : beforeTanks.has(tank.slot)
                      ? pose(beforeTanks.get(tank.slot))
                      : null,
            name: players.get(tank.slot)?.name || `Танк №${tank.slot + 1}`,
            self: Boolean(selfId && players.get(tank.slot)?.id === selfId),
            focused: focusSlot === tank.slot,
            color: colourFor(tank.slot),
          }),
        ),
    ),
    projectiles: Object.freeze(
      (frame?.projectiles || []).map((projectile) =>
        Object.freeze({
          id: projectile.id,
          owner: projectile.owner,
          position: point(
            (match?.partial ? null : nextProjectiles.get(projectile.id)?.position) ||
              projectile.position,
          ),
          velocity: point(projectile.velocity),
          previous: match?.partial
            ? null
            : nextFrame
              ? point(projectile.position)
              : beforeProjectiles.has(projectile.id)
                ? point(beforeProjectiles.get(projectile.id).position)
                : previousFrame && previousFrame.tick < frame.tick && mode !== "replay"
                  ? point({
                      // A surviving straight projectile has already completed this segment.
                      // Recover its last step, not a predicted future path or an invented shot.
                      x:
                        projectile.position.x -
                        projectile.velocity.x / (match?.rules?.tickRate || 20),
                      y:
                        projectile.position.y -
                        projectile.velocity.y / (match?.rules?.tickRate || 20),
                    })
                  : null,
          color: colourFor(projectile.owner),
        }),
      ),
    ),
    events: Object.freeze(
      visualEvents.map((event) =>
        Object.freeze({
          type: event.type,
          actor: event.actor,
          target: event.target,
          position: point(event.position),
        }),
      ),
    ),
    aim: aim && mode === "manual" ? point(aim) : null,
    mode,
  });
}
import { teamOf } from "./team-roster.js";
