import { supabase } from "../supabase";

export type CreditKind = "consume" | "purchase" | "grant";

export interface CreditTransaction {
  id: number;
  rider: string;
  kind: CreditKind;
  credits: number;
  createdAt: string;
}

interface TransactionRow {
  id: number;
  kind: CreditKind;
  credits: number;
  created_at: string;
  app_users: { name: string } | null;
}

export async function loadTransactions(): Promise<{
  rows: CreditTransaction[];
  consumed: number;
  purchased: number;
  granted: number;
  error: string | null;
}> {
  const [list, totals] = await Promise.all([
    supabase
      .from("credit_transactions")
      .select("id,kind,credits,created_at,app_users(name)")
      .order("created_at", { ascending: false })
      .limit(100),
    supabase.from("credit_transactions").select("kind,credits").limit(10000),
  ]);
  const error = list.error ?? totals.error;
  if (error) return { rows: [], consumed: 0, purchased: 0, granted: 0, error: transactionsError(error.code, error.message) };

  const rows = ((list.data ?? []) as unknown as TransactionRow[]).map((row) => ({
    id: row.id,
    rider: row.app_users?.name ?? "Unknown rider",
    kind: row.kind,
    credits: row.credits,
    createdAt: row.created_at,
  }));
  let consumed = 0;
  let purchased = 0;
  let granted = 0;
  for (const row of totals.data ?? []) {
    if (row.kind === "consume") consumed += row.credits;
    else if (row.kind === "purchase") purchased += row.credits;
    else if (row.kind === "grant") granted += row.credits;
  }
  return { rows, consumed, purchased, granted, error: null };
}

export async function loadWelcomeCredits(): Promise<{ credits: number | null; error: string | null }> {
  const { data, error } = await supabase.from("credit_settings").select("welcome_credits").eq("id", 1).maybeSingle();
  if (error) return { credits: null, error: settingsError(error.code, error.message) };
  return { credits: data?.welcome_credits ?? 20, error: null };
}

export async function saveWelcomeCredits(credits: number): Promise<string | null> {
  const { data, error } = await supabase.from("credit_settings").update({ welcome_credits: credits }).eq("id", 1).select("id");
  if (error) return settingsError(error.code, error.message);
  if (!data || data.length === 0) return "Free credits aren't set up yet. Run supabase/plans.sql again.";
  return null;
}

function settingsError(code: string | undefined, message: string): string {
  if (code === "PGRST205" || code === "42P01" || code === "PGRST204" || code === "42703") {
    return "Free credits aren't set up yet. Run supabase/plans.sql again.";
  }
  if (code === "42501") return "Your account isn't allowed to change free credits.";
  if (/failed to fetch|network/i.test(message)) return "Can't reach the server. Check your internet connection.";
  return `Couldn't save free credits: ${message}`;
}

function transactionsError(code: string | undefined, message: string): string {
  if (code === "PGRST205" || code === "42P01" || code === "PGRST204") {
    return "The transactions table isn't set up yet. Run supabase/dashboard.sql again.";
  }
  if (code === "42501") return "Your account isn't allowed to read transactions.";
  if (/failed to fetch|network/i.test(message)) return "Can't reach the server. Check your internet connection.";
  return `Couldn't load transactions: ${message}`;
}
