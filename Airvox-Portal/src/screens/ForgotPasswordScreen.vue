<script setup lang="ts">
import { ref } from "vue";
import { EMAIL_PATTERN, networkMessage } from "../auth";
import AuthLayout from "../components/AuthLayout.vue";
import CardHeader from "../components/CardHeader.vue";
import { snackbar } from "../snackbar";
import { supabase } from "../supabase";

const props = defineProps<{ initialEmail: string }>();
const emit = defineEmits<{ back: [email: string] }>();

const email = ref(props.initialEmail);
const invalid = ref(false);
const busy = ref(false);
const sentTo = ref<string | null>(null);

async function submit() {
  if (busy.value) return;
  const address = email.value.trim();
  if (!EMAIL_PATTERN.test(address)) {
    invalid.value = true;
    return;
  }
  invalid.value = false;
  busy.value = true;
  // The link brings the user back here, where App shows the new-password screen.
  const { error } = await supabase.auth.resetPasswordForEmail(address, { redirectTo: window.location.origin });
  busy.value = false;
  if (error) {
    snackbar.error(networkMessage(error.message, "Couldn't send the reset link. Try again."));
    return;
  }
  sentTo.value = address;
  snackbar.success("Reset link sent. Check your email.");
}
</script>

<template>
  <AuthLayout>
    <div v-if="sentTo" class="card">
      <CardHeader title="Check your email" :subtitle="`If ${sentTo} has a staff account, a link to reset the password is on its way.`" />
      <ul class="steps">
        <li>Open the email from Airvox and click the reset link.</li>
        <li>Choose a new password on the page that opens.</li>
        <li>The link works once and expires after an hour.</li>
      </ul>
      <button type="button" class="primary" @click="emit('back', sentTo)">Back to sign in</button>
      <p class="footer muted">
        No email?
        <button type="button" class="link-button" @click="sentTo = null">Try again</button>
      </p>
    </div>

    <form v-else class="card" novalidate @submit.prevent="submit">
      <CardHeader title="Forgot password" subtitle="Enter your staff email and we'll send you a link to set a new password." />

      <label class="field">
        <span>Email</span>
        <input
          v-model="email"
          type="email"
          autocomplete="email"
          placeholder="name@airvox.com"
          :aria-invalid="invalid || undefined"
          autofocus
        />
      </label>

      <p v-if="invalid" class="message error" role="alert">Enter a valid email address.</p>

      <button type="submit" class="primary" :disabled="busy">{{ busy ? "Sending…" : "Send reset link" }}</button>

      <p class="footer muted">
        Remembered it?
        <button type="button" class="link-button" @click="emit('back', email.trim())">Back to sign in</button>
      </p>
    </form>
  </AuthLayout>
</template>
