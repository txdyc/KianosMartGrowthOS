import { expect, test } from "vitest";
import { formatGhs } from "./format";

test("formatGhs", () => {
  expect(formatGhs(299)).toBe("GH₵ 299");
  expect(formatGhs("299.00")).toBe("GH₵ 299");
  expect(formatGhs(299.5)).toBe("GH₵ 299.50");
  expect(formatGhs(1299)).toBe("GH₵ 1,299");
  expect(formatGhs(null)).toBe("—");
  expect(formatGhs(undefined)).toBe("—");
  expect(formatGhs("")).toBe("—");
});
