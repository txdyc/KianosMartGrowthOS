/**
 * Formats an amount in Ghana cedi: integers without decimals, otherwise two
 * decimals, with thousands separators. Missing values become an em dash.
 */
export function formatGhs(amount: number | string | null | undefined): string {
  if (amount === null || amount === undefined || amount === "") return "—";
  const value = typeof amount === "string" ? Number(amount) : amount;
  if (!Number.isFinite(value)) return "—";
  const formatted = Number.isInteger(value)
    ? value.toLocaleString("en-US", { maximumFractionDigits: 0 })
    : value.toLocaleString("en-US", { minimumFractionDigits: 2, maximumFractionDigits: 2 });
  return `GH₵ ${formatted}`;
}
