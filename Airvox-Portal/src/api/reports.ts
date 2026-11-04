import { supabase } from "../supabase";

export type ReportPeriod = "today" | "7" | "30";

export interface RiderReport {
  id: string;
  name: string;
  left: number;
  used: number;
  bought: number;
  free: number;
  lastActiveAt: string | null;
}

export interface CreditReport {
  newRiders: number;
  opened: number;
  used: number;
  bought: number;
  free: number;
  left: number;
  riders: RiderReport[];
}

interface UserRow {
  id: string;
  name: string;
  wallet_balance: number | string;
  created_at: string;
  last_active_at: string | null;
}

interface ActivityRow {
  user_id: string;
  day: string;
}

interface CreditRow {
  user_id: string;
  kind: "consume" | "purchase" | "grant";
  credits: number;
  created_at: string;
}

export async function loadReport(period: ReportPeriod): Promise<{ report: CreditReport | null; error: string | null }> {
  const start = periodStart(period);
  const [users, activity, credits] = await Promise.all([
    supabase.from("app_users").select("id,name,wallet_balance,created_at,last_active_at"),
    supabase.from("user_activity").select("user_id,day").gte("day", dateKey(start)),
    supabase
      .from("credit_transactions")
      .select("user_id,kind,credits,created_at")
      .gte("created_at", start.toISOString())
      .limit(10000),
  ]);
  const error = users.error ?? activity.error ?? credits.error;
  if (error) return { report: null, error: reportError(error.code, error.message) };

  const people = (users.data ?? []) as UserRow[];
  const days = (activity.data ?? []) as ActivityRow[];
  const moves = (credits.data ?? []) as CreditRow[];
  const openedIds = new Set(days.map((row) => row.user_id));
  const byRider = new Map<string, { used: number; bought: number; free: number }>();
  let used = 0;
  let bought = 0;
  let free = 0;
  for (const row of moves) {
    const bucket = byRider.get(row.user_id) ?? { used: 0, bought: 0, free: 0 };
    if (row.kind === "consume") {
      bucket.used += row.credits;
      used += row.credits;
    } else if (row.kind === "purchase") {
      bucket.bought += row.credits;
      bought += row.credits;
    } else if (row.kind === "grant") {
      bucket.free += row.credits;
      free += row.credits;
    }
    byRider.set(row.user_id, bucket);
  }

  const riders = people
    .map((person) => {
      const totals = byRider.get(person.id) ?? { used: 0, bought: 0, free: 0 };
      return {
        id: person.id,
        name: person.name,
        left: Number(person.wallet_balance) || 0,
        used: totals.used,
        bought: totals.bought,
        free: totals.free,
        lastActiveAt: person.last_active_at,
      };
    })
    .sort((a, b) => b.used - a.used || a.name.localeCompare(b.name));

  return {
    report: {
      newRiders: people.filter((person) => new Date(person.created_at).getTime() >= start.getTime()).length,
      opened: openedIds.size,
      used,
      bought,
      free,
      left: riders.reduce((sum, rider) => sum + rider.left, 0),
      riders,
    },
    error: null,
  };
}

function periodStart(period: ReportPeriod): Date {
  const start = new Date();
  start.setHours(0, 0, 0, 0);
  if (period === "7") start.setDate(start.getDate() - 6);
  if (period === "30") start.setDate(start.getDate() - 29);
  return start;
}

function dateKey(date: Date): string {
  const month = String(date.getMonth() + 1).padStart(2, "0");
  const day = String(date.getDate()).padStart(2, "0");
  return `${date.getFullYear()}-${month}-${day}`;
}

function reportError(code: string | undefined, message: string): string {
  if (code === "PGRST205" || code === "42P01" || code === "PGRST204") {
    return "The report tables aren't set up yet. Run supabase/dashboard.sql again.";
  }
  if (code === "42501") return "Your account isn't allowed to read reports.";
  if (/failed to fetch|network/i.test(message)) return "Can't reach the server. Check your internet connection.";
  return `Couldn't load the report: ${message}`;
}
