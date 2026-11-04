import { reactive } from "vue";
import { supabase } from "../supabase";

/** What supabase/dashboard.sql's dashboard_summary() returns. */
export interface DashboardSummary {
  generatedAt: string;
  totalUsers: number;
  newUsers7d: number;
  activeToday: number;
  /** Signed in and checked in within the last 5 minutes. Missing until dashboard.sql is run again. */
  loggedIn?: number;
  /** Same count as loggedIn. Kept so an older dashboard response still has a number. */
  onlineNow?: number;
  loggedInUsers?: { id: string; name: string; platform: string | null; lastActiveAt: string }[];
  liveChannels: number;
  liveOffline: number;
  liveInternet: number;
  openSos: number;
  oldestOpenSosAt: string | null;
  topupsThisMonth: number;
  topupsLastMonth: number;
  dailyActive: { day: string; count: number }[];
  openReports: number;
  failedPayments: number;
  pendingAccounts: number;
  rollouts: { platform: string; version: string; percent: number }[];
  androidUsers: number;
  iosUsers: number;
  incidents: { id: number; rider: string; location: string | null; raisedAt: string; status: "Open" | "Resolved" }[];
  newestUsers: { id: string; name: string; platform: string; status: "Active" | "Pending" | "Suspended"; joinedAt: string }[];
}

// Shared so the sidebar's SOS badge and the Dashboard show the same numbers.
export const dashboard = reactive({
  data: null as DashboardSummary | null,
  loading: false,
  error: null as string | null,
});

export async function loadDashboard(): Promise<boolean> {
  if (dashboard.loading) return false;
  dashboard.loading = true;
  const { data, error } = await supabase.rpc("dashboard_summary");
  dashboard.loading = false;
  if (error) {
    dashboard.error = dashboardError(error.code, error.message);
    return false;
  }
  dashboard.error = null;
  dashboard.data = data as DashboardSummary;
  return true;
}

function dashboardError(code: string | undefined, message: string): string {
  // PGRST202: the function doesn't exist yet.
  if (code === "PGRST202" || code === "42883") return "The dashboard API isn't set up in Supabase yet. Run supabase/dashboard.sql.";
  if (code === "42501") return "Your account isn't allowed to read the dashboard.";
  if (/failed to fetch|network/i.test(message)) return "Can't reach the server. Check your internet connection.";
  return `Couldn't load the dashboard: ${message}`;
}
