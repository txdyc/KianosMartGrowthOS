"use client";

import type { ReactNode } from "react";
import { useI18n } from "@/i18n";
import type { TFunction } from "@/i18n";
import { LIST_FIELDS, SCALAR_FIELDS, listFieldToText } from "@/lib/facts";
import type { ListField, ScalarField } from "@/lib/facts";
import type { FactsJson, FieldSource } from "@/lib/types";

export const fieldInputClass =
  "w-full rounded-md border border-zinc-300 bg-white px-2.5 py-1.5 text-sm dark:border-zinc-700 dark:bg-zinc-950 disabled:opacity-60";

/**
 * The editable draft fields (C3/C6): the eight scalars plus the three
 * multi-line list fields, each with its provenance badge. Extracted from the
 * facts page so the batch fact station edits the same way; the station passes
 * `highlight` to ring the field the keyboard is on.
 */
export function FactsFields({
  facts,
  fieldSources,
  disabled,
  highlight,
  onScalar,
  onList,
}: {
  facts: FactsJson;
  fieldSources: Record<string, FieldSource> | undefined;
  disabled: boolean;
  highlight?: string;
  onScalar: (field: ScalarField, value: string) => void;
  onList: (field: ListField, text: string) => void;
}) {
  const { t } = useI18n();
  const ring = " ring-2 ring-zinc-900 dark:ring-zinc-100";
  return (
    <>
      <div className="grid grid-cols-1 gap-4 sm:grid-cols-2">
        {SCALAR_FIELDS.map((field) => (
          <FieldRow
            key={field}
            label={t(`facts.field.${field}`)}
            source={fieldSources?.[field] ?? "NONE"}
            disabled={disabled}
          >
            <input
              id={`facts-field-${field}`}
              type={field === "powerW" ? "number" : "text"}
              className={fieldInputClass + (highlight === field ? ring : "")}
              value={String(facts[field] ?? "")}
              disabled={disabled}
              onChange={(event) => onScalar(field, event.target.value)}
            />
          </FieldRow>
        ))}
      </div>

      {LIST_FIELDS.map((field) => (
        <FieldRow
          key={field}
          label={t(`facts.field.${field}`)}
          source={fieldSources?.[field] ?? "NONE"}
          disabled={disabled}
        >
          <textarea
            id={`facts-field-${field}`}
            className={fieldInputClass + (highlight === field ? ring : "")}
            rows={3}
            value={listFieldToText(facts[field])}
            disabled={disabled}
            onChange={(event) => onList(field, event.target.value)}
          />
        </FieldRow>
      ))}
    </>
  );
}

/** One labelled input plus its provenance badge. */
function FieldRow({
  label,
  source,
  disabled,
  children,
}: {
  label: string;
  source: string;
  disabled: boolean;
  children: ReactNode;
}) {
  const { t } = useI18n();
  return (
    <div className="flex flex-col gap-1">
      <div className="flex items-center justify-between">
        <label className="text-sm font-medium">{label}</label>
        {!disabled ? (
          <span
            className={`rounded px-1.5 py-0.5 text-[10px] font-medium ${
              source === "MANUAL"
                ? "bg-violet-100 text-violet-700 dark:bg-violet-900/40 dark:text-violet-300"
                : "bg-zinc-100 text-zinc-500 dark:bg-zinc-800 dark:text-zinc-400"
            }`}
          >
            {sourceLabel(source, t)}
          </span>
        ) : null}
      </div>
      {children}
    </div>
  );
}

const SOURCE_LABEL_KEYS: Record<
  string,
  "facts.source.P5" | "facts.source.PROMO" | "facts.source.WOO_TEXT" | "facts.source.MANUAL" | "facts.source.NONE"
> = {
  P5: "facts.source.P5",
  PROMO: "facts.source.PROMO",
  WOO_TEXT: "facts.source.WOO_TEXT",
  MANUAL: "facts.source.MANUAL",
  NONE: "facts.source.NONE",
};

function sourceLabel(source: string, t: TFunction): string {
  return t(SOURCE_LABEL_KEYS[source] ?? "facts.source.NONE");
}