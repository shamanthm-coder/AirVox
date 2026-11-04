import { supabase } from "../supabase";
import type { UserStatus } from "../sampleData";

/** A rider is logged in when the app is signed in and has checked in recently. */
const LOGGED_IN_WINDOW_MS = 5 * 60 * 1000;

export interface ManagedUser {
  id: string;
  name: string;
  phone: string | null;
  email: string | null;
  platform: string | null;
  status: UserStatus;
  wallet: number;
  joinedAt: string;
  lastActiveAt: string | null;
  loggedIn: boolean;
}

interface UserRow {
  id: string;
  name: string;
  phone: string | null;
  email: string | null;
  platform: string | null;
  status: UserStatus;
  wallet_balance: number | string;
  created_at: string;
  last_active_at: string | null;
  logged_in: boolean;
}

export function isLoggedIn(loggedIn: boolean, lastActiveAt: string | null, now = Date.now()): boolean {
  if (!loggedIn || !lastActiveAt) return false;
  return now - new Date(lastActiveAt).getTime() <= LOGGED_IN_WINDOW_MS;
}

export async function loadUsers(): Promise<{ users: ManagedUser[]; error: string | null }> {
  const { data, error } = await supabase
    .from("app_users")
    .select("id,name,phone,email,platform,status,wallet_balance,created_at,last_active_at,logged_in")
    .order("last_active_at", { ascending: false, nullsFirst: false });
  if (error) return { users: [], error: usersError(error.code, error.message) };
  const now = Date.now();
  const users = ((data ?? []) as UserRow[]).map((row) => ({
    id: row.id,
    name: row.name,
    phone: row.phone,
    email: row.email,
    platform: row.platform,
    status: row.status,
    wallet: Number(row.wallet_balance) || 0,
    joinedAt: row.created_at,
    lastActiveAt: row.last_active_at,
    loggedIn: isLoggedIn(row.logged_in, row.last_active_at, now),
  }));
  return { users, error: null };
}

export async function updateUserStatus(id: string, status: UserStatus): Promise<string | null> {
  const { error } = await supabase.from("app_users").update({ status }).eq("id", id);
  if (!error) return null;
  if (error.code === "42501") return "Your account isn't allowed to change riders.";
  return `Couldn't update this rider: ${error.message}`;
}

function usersError(code: string | undefined, message: string): string {
  if (code === "PGRST205" || code === "42P01") return "The users table isn't set up in Supabase yet. Run supabase/dashboard.sql.";
  if (code === "PGRST204" || code === "42703") return "The users table is missing the logged-in column. Run supabase/dashboard.sql again.";
  if (code === "42501") return "Your account isn't allowed to read riders.";
  if (/failed to fetch|network/i.test(message)) return "Can't reach the server. Check your internet connection.";
  return `Couldn't load users: ${message}`;
}
