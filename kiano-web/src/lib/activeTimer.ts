/**
 * The active-time timer of the two batch workbenches (C6 Task 8). Time only
 * counts while the page is visible and while the reviewer is active: each
 * activity extends the active window by `idleMs`, so idle stretches over 60 s
 * fall out of the metric (Review Focus 5). Time comes from the injected
 * `now`, which keeps the logic pure and testable.
 */
export interface ActiveTimer {
  readonly idleMs: number;
  readonly beatMs: number;
  /** Marks input (keyboard, mouse, scroll) → the active window restarts. */
  activity(): void;
  /** Hidden pages pause the clock; becoming visible counts as activity. */
  setVisible(visible: boolean): void;
  /** Active seconds since the previous tick (and moves the window). */
  tick(): number;
  /** The same at the end of a session. */
  flush(): number;
}

export function createActiveTimer(
  options: { now?: () => number; idleMs?: number; beatMs?: number } = {},
): ActiveTimer {
  const now = options.now ?? (() => Date.now());
  const idleMs = options.idleMs ?? 60_000;
  const beatMs = options.beatMs ?? 15_000;
  let lastActivityAt = now();
  let lastTickAt = lastActivityAt;
  let visible = true;
  // active time worked before the page was hidden, not yet reported
  let pendingMs = 0;

  function activeSinceTick(current: number): number {
    if (!visible) {
      return 0;
    }
    const activeUntil = Math.min(current, lastActivityAt + idleMs);
    return Math.max(0, activeUntil - lastTickAt);
  }

  function deltaSeconds(): number {
    const current = now();
    const active = pendingMs + activeSinceTick(current);
    pendingMs = 0;
    lastTickAt = current;
    return active / 1000;
  }

  return {
    idleMs,
    beatMs,
    activity() {
      lastActivityAt = now();
    },
    setVisible(next: boolean) {
      const current = now();
      // hiding pauses the clock but keeps what was worked until now
      pendingMs += activeSinceTick(current);
      lastTickAt = current;
      if (next) {
        lastActivityAt = current;
      }
      visible = next;
    },
    tick: deltaSeconds,
    flush: deltaSeconds,
  };
}
