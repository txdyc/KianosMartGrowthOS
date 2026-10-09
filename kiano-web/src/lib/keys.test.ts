import { expect, test } from "vitest";
import { actionFor, keymap, REJECT_REASONS } from "./keys";
import type { KeyEventLike } from "./keys";

const input = { tagName: "INPUT" } as unknown as EventTarget;
const body = { tagName: "BODY" } as unknown as EventTarget;

test("shortcuts are inactive inside inputs, only Esc still works", () => {
  const inside: KeyEventLike = { key: "Enter", target: input };
  expect(actionFor(inside, { station: "FACTS", factsComplete: true })).toBeNull();
  expect(actionFor({ key: "Escape", target: input }, { station: "FACTS" })).toBe("CANCEL");
  expect(
    actionFor({ key: "Enter", target: body }, { station: "FACTS", factsComplete: true }),
  ).toBe("CONFIRM_ALL");
});

test("facts station: Enter locks only with all six fields filled", () => {
  expect(keymap({ station: "FACTS", factsComplete: false }).Enter).toBeUndefined();
  expect(keymap({ station: "FACTS", factsComplete: true }).Enter).toBe("CONFIRM_ALL");
  const map = keymap({ station: "FACTS", factsComplete: false });
  expect(map.j).toBe("NEXT_FIELD");
  expect(map.k).toBe("PREV_FIELD");
  expect(map.e).toBe("EDIT_FIELD");
  expect(map.s).toBe("SKIP_SKU");
  expect(map["?"]).toBe("HELP");
});

test("review station: digits pick the reject reasons", () => {
  expect(keymap({ station: "REVIEW" })["3"]).toBeUndefined();

  const rejecting = keymap({ station: "REVIEW", rejecting: true });
  REJECT_REASONS.forEach((_, index) => {
    expect(rejecting[String(index + 1)]).toBe(`REJECT_REASON_${index + 1}`);
  });
  expect(rejecting["0"]).toBeUndefined();
  expect(rejecting["8"]).toBeUndefined();
});

test("review station: arrows, approve, reject, regenerate, next", () => {
  const map = keymap({ station: "REVIEW" });
  expect(map.ArrowRight).toBe("NEXT_ASSET");
  expect(map.ArrowLeft).toBe("PREV_ASSET");
  expect(map[" "]).toBe("APPROVE");
  expect(map.x).toBe("REJECT");
  expect(map.g).toBe("REGENERATE");
  expect(map.a).toBe("APPROVE_REMAINING");
  expect(map.n).toBe("NEXT_SKU");
  expect(actionFor({ key: " ", target: body }, { station: "REVIEW" })).toBe("APPROVE");
});