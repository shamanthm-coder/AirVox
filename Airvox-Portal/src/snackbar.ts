import { reactive } from "vue";

export type SnackKind = "success" | "error";

export interface Snack {
  id: number;
  kind: SnackKind;
  text: string;
}

export const SHOW_MS: Record<SnackKind, number> = { success: 5000, error: 6000 };

type SnackStore = { items: Snack[]; nextId: number };

// Kept on the page itself so a refresh of this file still talks to the same list the screen draws.
const store: SnackStore = ((globalThis as { __airvoxSnacks?: SnackStore }).__airvoxSnacks ??= {
  items: reactive<Snack[]>([]),
  nextId: 1,
});

export const snacks = store.items;

function show(kind: SnackKind, text: string) {
  while (snacks.length >= 3) snacks.shift();
  snacks.push({ id: store.nextId++, kind, text });
}

export function dismissSnack(id: number) {
  const index = snacks.findIndex((s) => s.id === id);
  if (index >= 0) snacks.splice(index, 1);
}

export const snackbar = {
  success: (text: string) => show("success", text),
  error: (text: string) => show("error", text),
};
