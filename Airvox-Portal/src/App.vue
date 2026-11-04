<script setup lang="ts">
import { onBeforeUnmount, onMounted, ref } from "vue";
import { dashboard } from "./api/dashboard";
import SnackbarHost from "./components/SnackbarHost.vue";
import DashboardScreen from "./screens/DashboardScreen.vue";
import ForgotPasswordScreen from "./screens/ForgotPasswordScreen.vue";
import LoginScreen from "./screens/LoginScreen.vue";
import ResetPasswordScreen from "./screens/ResetPasswordScreen.vue";
import { snackbar } from "./snackbar";
import { type StaffMember, checkStaff, supabase } from "./supabase";

type Screen = "login" | "forgot" | "reset";

// Read before Supabase clears the address: the reset email links back with these.
const startHash = new URLSearchParams(window.location.hash.slice(1));
const openedFromReset = startHash.get("type") === "recovery";
const linkError = startHash.get("error_description");

const staff = ref<StaffMember | null>(null);
const screen = ref<Screen>(openedFromReset ? "reset" : "login");
const email = ref("");
// True until the saved session, if any, has been checked.
const loading = ref(!openedFromReset);

// Set while this tab is signing out on purpose, so that isn't reported as an ended session.
let leaving = false;

const { data: listener } = supabase.auth.onAuthStateChange((event) => {
  if (event === "PASSWORD_RECOVERY") screen.value = "reset";
  if (event === "SIGNED_OUT" && staff.value && !leaving) {
    // Signed out elsewhere (another tab) or the session expired.
    email.value = staff.value.email;
    staff.value = null;
    dashboard.data = null;
    screen.value = "login";
    snackbar.error("Your session ended. Sign in again.");
  }
});
onBeforeUnmount(() => listener.subscription.unsubscribe());

onMounted(async () => {
  if (linkError) {
    snackbar.error(/expired|invalid/i.test(linkError) ? "This reset link has expired. Ask for a new one." : linkError);
    history.replaceState(null, "", window.location.pathname);
  }
  if (openedFromReset) return;
  const { data } = await supabase.auth.getSession();
  const user = data.session?.user;
  if (user) {
    const result = await checkStaff(user.id, user.email ?? "");
    if (result.ok) staff.value = result.staff;
    else await supabase.auth.signOut();
  }
  loading.value = false;
});

async function signedIn(userId: string, address: string): Promise<string | null> {
  const result = await checkStaff(userId, address);
  if (result.ok) {
    staff.value = result.staff;
    snackbar.success(`Signed in as ${address}`);
    return null;
  }
  await supabase.auth.signOut();
  return result.message;
}

async function signOut() {
  leaving = true;
  // "local" signs out this browser only; staff signed in elsewhere stay signed in.
  const { error } = await supabase.auth.signOut({ scope: "local" });
  email.value = staff.value?.email ?? email.value;
  staff.value = null;
  dashboard.data = null;
  screen.value = "login";
  leaving = false;
  if (error) snackbar.error("Signed out on this computer, but the server didn't respond.");
  else snackbar.success("Signed out.");
}

function goToSignIn(address = email.value) {
  email.value = address;
  screen.value = "login";
}

function openForgot(address: string) {
  email.value = address;
  screen.value = "forgot";
}
</script>

<template>
  <div v-if="loading" class="loading" role="status">Loading…</div>
  <ResetPasswordScreen v-else-if="screen === 'reset'" @done="goToSignIn()" />
  <DashboardScreen v-else-if="staff" :staff="staff" :on-sign-out="signOut" />
  <ForgotPasswordScreen v-else-if="screen === 'forgot'" :initial-email="email" @back="goToSignIn" />
  <LoginScreen v-else :key="email" :initial-email="email" :on-signed-in="signedIn" @forgot-password="openForgot" />
  <SnackbarHost />
</template>
