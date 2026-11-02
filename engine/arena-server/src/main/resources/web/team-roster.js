/** Server assignments take precedence over any legacy equal-size layout. */
export function teamOf(match, slot) {
  const roster = match?.recon;
  if (!roster) return slot;
  if (Number.isInteger(roster.teamBySlot?.[slot])) return roster.teamBySlot[slot];
  if (roster.participantSlots && roster.teams) {
    const small = Math.floor(roster.participantSlots / roster.teams);
    const largerTeams = roster.participantSlots % roster.teams;
    const boundary = largerTeams * (small + 1);
    return slot < boundary
      ? Math.floor(slot / (small + 1))
      : largerTeams + Math.floor((slot - boundary) / small);
  }
  return Math.floor(slot / (roster.teamSize || 3));
}
