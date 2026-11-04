import { supabase } from "../supabase";

export interface CreditPlan {
  id: number;
  name: string;
  credits: number;
  rupees: number;
  active: boolean;
}

interface PlanRow {
  id: number;
  name: string;
  credits: number;
  rupees: number;
  active: boolean;
}

export async function loadPlans(): Promise<{ plans: CreditPlan[]; error: string | null }> {
  const { data, error } = await supabase.from("credit_plans").select("id,name,credits,rupees,active").order("credits");
  if (error) return { plans: [], error: plansError(error.code, error.message) };
  return { plans: (data ?? []) as PlanRow[], error: null };
}

export async function savePlan(plan: { id?: number; name: string; credits: number; rupees: number; active: boolean }): Promise<string | null> {
  const row = { name: plan.name, credits: plan.credits, rupees: plan.rupees, active: plan.active };
  const result = plan.id
    ? await supabase.from("credit_plans").update(row).eq("id", plan.id)
    : await supabase.from("credit_plans").insert(row);
  return result.error ? plansError(result.error.code, result.error.message) : null;
}

export async function deletePlan(id: number): Promise<string | null> {
  const { error } = await supabase.from("credit_plans").delete().eq("id", id);
  return error ? plansError(error.code, error.message) : null;
}

function plansError(code: string | undefined, message: string): string {
  if (code === "PGRST205" || code === "42P01" || code === "PGRST204" || code === "42703") {
    return "The plans table needs a price. Run supabase/plans.sql again.";
  }
  if (code === "42501") return "Your account isn't allowed to change plans.";
  if (/failed to fetch|network/i.test(message)) return "Can't reach the server. Check your internet connection.";
  return `Couldn't save plans: ${message}`;
}
