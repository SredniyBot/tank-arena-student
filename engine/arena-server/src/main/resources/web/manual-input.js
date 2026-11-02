/** Preserve a short press until the next command; holding repeats intent, never server actions. */
export function createButtonInput() {
  let held = false,
    pressed = false,
    generation = 0;
  return Object.freeze({
    press() {
      held = true;
      pressed = true;
    },
    release() {
      held = false;
    },
    consume() {
      const requested = held || pressed;
      pressed = false;
      return requested;
    },
    restorePoint() {
      const captured = generation;
      return () => {
        // A later press stays queued; blur, death or exit invalidates older attempts.
        if (captured === generation) pressed = true;
      };
    },
    reset() {
      generation++;
      held = false;
      pressed = false;
    },
  });
}
