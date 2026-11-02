/** Browser pacing follows server budgets; rendering never advances authoritative physics. */
export function matchPollMillis(info, controlling) {
  if (!controlling) return Math.max(100, info?.spectatorPollMillis || 500);
  const period = Math.max(
    Math.ceil(1000 / (info?.maxStepRate || 10)),
    Math.ceil((info?.commandTimeoutMillis || 100) / (info?.controlPeriodTicks || 1)),
  );
  return Math.max(info?.playerPollMillis || 50, Math.ceil(period / 2));
}

/** Interpolate the observed cadence, not just its first 100 ms. Never extrapolate physics. */
export function interpolationMillis(elapsed) {
  return elapsed > 1500 ? 100 : Math.max(20, Math.min(500, elapsed));
}

/** Allow each display frame, while retaining the accessibility motion limit. */
export function shouldDraw(now, lastFrame, reducedMotion) {
  return !reducedMotion || now - lastFrame >= 80;
}

/** Select historical rules without mutating the snapshot or leaking future rule changes. */
export function replayMatchAt(match, changes, tick) {
  let selected = null;
  for (const change of changes || []) {
    if (change.fromTick <= tick && (!selected || change.fromTick >= selected.fromTick))
      selected = change;
  }
  if (!selected) return match;
  return {
    ...match,
    rules: selected.rules,
    live: match.live ? { ...match.live, respawnTicks: selected.respawnTicks } : match.live,
  };
}
