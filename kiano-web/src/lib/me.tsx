"use client";

import { createContext, useContext } from "react";

/** The signed-in user, as returned by GET /api/v1/auth/me. */
export type Me = { userId: number; email: string; name: string; role: string };

const MeContext = createContext<Me | null>(null);

/** Provided by src/app/(app)/layout.tsx. Null until /me resolves. */
export const MeProvider = MeContext.Provider;

export function useMe(): Me | null {
  return useContext(MeContext);
}

/** OWNER and OPERATOR may change data. */
export function canOperate(role: string | undefined | null): boolean {
  return role === "OPERATOR" || role === "OWNER";
}

export function isOwner(role: string | undefined | null): boolean {
  return role === "OWNER";
}
