import { createClient } from "@supabase/supabase-js";

const url = import.meta.env.VITE_SUPABASE_URL as string | undefined;
const key = import.meta.env.VITE_SUPABASE_ANON_KEY as string | undefined;

export const supabaseConfigured = Boolean(url && key);

const REMEMBER_KEY = "airvox-remember";

function rememberSession(): boolean {
  try {
    return localStorage.getItem(REMEMBER_KEY) !== "no";
  } catch {
    return false;
  }
}

export function setRememberSession(remember: boolean) {
  try {
    localStorage.setItem(REMEMBER_KEY, remember ? "yes" : "no");
  } catch {
    // Private windows can block storage; the session then lasts for this tab only.
  }
}

// "Keep me signed in" off keeps the session in this tab only.
const sessionStore = {
  getItem(name: string) {
    try {
      return localStorage.getItem(name) ?? sessionStorage.getItem(name);
    } catch {
      return null;
    }
  },
  setItem(name: string, value: string) {
    try {
      (rememberSession() ? localStorage : sessionStorage).setItem(name, value);
    } catch {
      // Ignored; see setRememberSession.
    }
  },
  removeItem(name: string) {
    try {
      localStorage.removeItem(name);
      sessionStorage.removeItem(name);
    } catch {
      // Ignored; see setRememberSession.
    }
  },
};

export const supabase = createClient(url ?? "http://localhost", key ?? "missing", {
  auth: { storage: sessionStore, persistSession: true, autoRefreshToken: true },
});

export interface StaffMember {
  email: string;
  role: string;
}

export type StaffCheck =
  | { ok: true; staff: StaffMember }
  | { ok: false; message: string };

/** Signed-in users get into the portal only if they have a row in public.staff. */
export async function checkStaff(userId: string, email: string): Promise<StaffCheck> {
  const { data, error } = await supabase.from("staff").select("role").eq("user_id", userId).maybeSingle();
  if (error) {
    if (error.code === "PGRST205" || error.code === "42P01") {
      return { ok: false, message: "The staff table isn't set up in Supabase yet. Run supabase/setup.sql first." };
    }
    return { ok: false, message: `Couldn't check staff access: ${error.message}` };
  }
  if (!data) return { ok: false, message: "This account isn't on the Airvox staff list." };
  return { ok: true, staff: { email, role: data.role } };
}
