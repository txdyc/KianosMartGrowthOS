import { expect, test } from "vitest";
import { createActiveTimer } from "./activeTimer";

test("idle stretches over 60 s are not counted", () => {
  let now = 0;
  const timer = createActiveTimer({ now: () => now });
  timer.activity();

  // the first 60 s after the last activity count, the idle rest does not
  now = 120_000;
  expect(timer.tick()).toBe(60);
});

test("hidden time is not counted", () => {
  let now = 0;
  const timer = createActiveTimer({ now: () => now });
  timer.activity();

  timer.setVisible(false);
  now = 10_000;
  expect(timer.tick()).toBe(0);

  // becoming visible again counts as fresh activity
  timer.setVisible(true);
  now = 25_000;
  expect(timer.tick()).toBe(15);
});

test("ticks add up to the active total", () => {
  let now = 0;
  const timer = createActiveTimer({ now: () => now });
  timer.activity();

  now = 10_000;
  const first = timer.tick();

  now = 15_000;
  timer.activity();
  now = 25_000;
  const second = timer.tick();

  expect(first).toBe(10);
  expect(second).toBe(15);
  expect(first + second).toBe(25);
});

test("flush returns the last delta and resets the window", () => {
  let now = 0;
  const timer = createActiveTimer({ now: () => now, beatMs: 5_000 });
  timer.activity();

  now = 5_000;
  expect(timer.flush()).toBe(5);
  expect(timer.beatMs).toBe(5_000);

  now = 8_000;
  expect(timer.tick()).toBe(3);
});