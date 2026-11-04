<script setup lang="ts">
import { onMounted, ref } from "vue";
import { loadWelcomeCredits, saveWelcomeCredits } from "../api/transactions";
import { formatNumber } from "../format";
import { snackbar } from "../snackbar";
import ConfirmDialog from "./ConfirmDialog.vue";

const gift = ref("20");
const draft = ref<string | number>("20");
const editing = ref(false);
const confirming = ref(false);
const saving = ref(false);

onMounted(loadGift);

async function loadGift() {
  const result = await loadWelcomeCredits();
  if (result.error) {
    snackbar.error(result.error);
    return;
  }
  if (result.credits != null) gift.value = String(result.credits);
}

function startEdit() {
  draft.value = gift.value;
  editing.value = true;
}

function cancelEdit() {
  editing.value = false;
}

function askSave() {
  const text = draft.value == null ? "" : String(draft.value).trim();
  const amount = Number(text);
  if (text === "" || !Number.isInteger(amount) || amount < 0 || amount > 10000) {
    snackbar.error("Free credits must be a whole number from 0 to 10,000.");
    return;
  }
  confirming.value = true;
}

async function confirmSave() {
  const amount = Number(draft.value);
  saving.value = true;
  const message = await saveWelcomeCredits(amount);
  saving.value = false;
  if (message) {
    snackbar.error(message);
    return;
  }
  gift.value = String(amount);
  confirming.value = false;
  editing.value = false;
  snackbar.success(`${amount} free credits saved. A new rider gets them on first sign-in.`);
}
</script>

<template>
  <form class="box toolbar" @submit.prevent="askSave">
    <div>
      <h2>Free credits</h2>
      <p class="muted">A new rider gets this many free credits. Riders who already signed in keep what they have.</p>
    </div>
    <div class="gift-actions">
      <template v-if="editing">
        <input v-model="draft" class="search gift-input" type="number" min="0" max="10000" aria-label="Free credits for a new rider" />
        <button type="submit" class="primary small" :disabled="saving">Save</button>
        <button type="button" class="ghost small" :disabled="saving" @click="cancelEdit">Cancel</button>
      </template>
      <template v-else>
        <strong class="gift-value">{{ formatNumber(Number(gift) || 0) }}</strong>
        <button type="button" class="ghost small" @click="startEdit">Edit</button>
      </template>
    </div>
  </form>

  <ConfirmDialog
    v-if="confirming"
    title="Save free credits?"
    :message="Number(draft) === 0
      ? 'New riders will not get free credits. Riders who already signed in keep what they have.'
      : `New riders will get ${formatNumber(Number(draft))} free credits on first sign-in. Riders who already signed in keep what they have.`"
    confirm-label="Save"
    busy-label="Saving…"
    :busy="saving"
    @confirm="confirmSave"
    @cancel="confirming = false"
  />
</template>
