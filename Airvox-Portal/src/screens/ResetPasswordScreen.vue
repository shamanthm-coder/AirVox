<script setup lang="ts">
import { ref } from "vue";
import { MIN_PASSWORD, networkMessage } from "../auth";
import AuthLayout from "../components/AuthLayout.vue";
import CardHeader from "../components/CardHeader.vue";
import PasswordInput from "../components/PasswordInput.vue";
import { snackbar } from "../snackbar";
import { supabase } from "../supabase";

// Shown after the user opens the reset link from their email.
const emit = defineEmits<{ done: [] }>();

const password = ref("");
const confirm = ref("");
const problem = ref<{ field: "password" | "confirm"; text: string } | null>(null);
const busy = ref(false);

async function submit() {
  if (busy.value) return;
  if (password.value.length < MIN_PASSWORD) {
    problem.value = { field: "password", text: `Use at least ${MIN_PASSWORD} characters.` };
    return;
  }
  if (password.value !== confirm.value) {
    problem.value = { field: "confirm", text: "The two passwords don't match." };
    return;
  }
  problem.value = null;
  busy.value = true;
  const { error } = await supabase.auth.updateUser({ password: password.value });
  if (error) {
    busy.value = false;
    snackbar.error(resetMessage(error.message));
    return;
  }
  // Start clean: sign in again with the new password.
  await supabase.auth.signOut();
  snackbar.success("Password updated. Sign in with your new password.");
  emit("done");
}

async function cancel() {
  await supabase.auth.signOut();
  emit("done");
}

function resetMessage(message: string): string {
  if (/same.*password|different from the old/i.test(message)) return "Choose a password you haven't used before.";
  if (/session|jwt|expired/i.test(message)) return "This reset link has expired. Ask for a new one.";
  return networkMessage(message, "Couldn't save the new password. Try again.");
}
</script>

<template>
  <AuthLayout>
    <form class="card" novalidate @submit.prevent="submit">
      <CardHeader title="Set a new password" subtitle="Choose a new password for your staff account." />

      <label class="field">
        <span>New password</span>
        <PasswordInput
          v-model="password"
          :placeholder="`At least ${MIN_PASSWORD} characters`"
          autocomplete="new-password"
          :invalid="problem?.field === 'password'"
        />
      </label>

      <label class="field">
        <span>Confirm new password</span>
        <PasswordInput v-model="confirm" placeholder="Type it again" autocomplete="new-password" :invalid="problem?.field === 'confirm'" />
      </label>

      <p v-if="problem" class="message error" role="alert">{{ problem.text }}</p>

      <button type="submit" class="primary" :disabled="busy">{{ busy ? "Saving…" : "Save new password" }}</button>

      <p class="footer muted">
        <button type="button" class="link-button" @click="cancel">Cancel and go to sign in</button>
      </p>
    </form>
  </AuthLayout>
</template>
