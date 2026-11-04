<script setup lang="ts">
import { ref } from "vue";
import { EMAIL_PATTERN, MIN_PASSWORD, networkMessage } from "../auth";
import AuthLayout from "../components/AuthLayout.vue";
import CardHeader from "../components/CardHeader.vue";
import PasswordInput from "../components/PasswordInput.vue";
import { snackbar } from "../snackbar";
import { setRememberSession, supabase, supabaseConfigured } from "../supabase";

const props = defineProps<{
  initialEmail: string;
  /** Resolves to a reason when the account may not use the portal. */
  onSignedIn: (userId: string, email: string) => Promise<string | null>;
}>();
const emit = defineEmits<{ forgotPassword: [email: string] }>();

const email = ref(props.initialEmail);
const password = ref("");
const remember = ref(true);
const busy = ref(false);
const fieldError = ref<{ field: "email" | "password"; text: string } | null>(null);

async function submit() {
  if (busy.value) return;
  if (!supabaseConfigured) {
    snackbar.error("Supabase isn't set up. Add the keys to .env.local and restart.");
    return;
  }
  const address = email.value.trim();
  if (!EMAIL_PATTERN.test(address)) {
    fieldError.value = { field: "email", text: "Enter a valid email address." };
    return;
  }
  if (password.value.length < MIN_PASSWORD) {
    fieldError.value = { field: "password", text: `Password must be at least ${MIN_PASSWORD} characters.` };
    return;
  }
  fieldError.value = null;
  busy.value = true;
  setRememberSession(remember.value);
  const { data, error } = await supabase.auth.signInWithPassword({ email: address, password: password.value });
  if (error || !data.user) {
    busy.value = false;
    snackbar.error(signInMessage(error?.message));
    return;
  }
  const refusal = await props.onSignedIn(data.user.id, data.user.email ?? address);
  if (refusal) {
    busy.value = false;
    snackbar.error(refusal);
  }
}

function signInMessage(message?: string): string {
  if (message && /invalid login credentials/i.test(message)) return "Wrong email or password.";
  if (message && /email not confirmed/i.test(message)) return "This email isn't confirmed yet. Ask an administrator.";
  return networkMessage(message, "Couldn't sign in. Try again.");
}
</script>

<template>
  <AuthLayout>
    <form class="card" novalidate @submit.prevent="submit">
      <CardHeader title="Staff sign in" subtitle="Use your Airvox staff account." />

      <label class="field">
        <span>Email</span>
        <input
          v-model="email"
          type="email"
          autocomplete="email"
          placeholder="name@airvox.com"
          :aria-invalid="fieldError?.field === 'email' || undefined"
        />
      </label>

      <label class="field">
        <span class="field-row">
          Password
          <button type="button" class="link-button" @click="emit('forgotPassword', email.trim())">Forgot password?</button>
        </span>
        <PasswordInput
          v-model="password"
          placeholder="Your password"
          autocomplete="current-password"
          :invalid="fieldError?.field === 'password'"
        />
      </label>

      <label class="check">
        <input v-model="remember" type="checkbox" />
        <span>Keep me signed in</span>
      </label>

      <p v-if="fieldError" class="message error" role="alert">{{ fieldError.text }}</p>

      <button type="submit" class="primary" :disabled="busy">{{ busy ? "Signing in…" : "Sign in" }}</button>

      <p class="footer muted">Staff only. Ask an administrator if you need an account.</p>
    </form>
  </AuthLayout>
</template>
