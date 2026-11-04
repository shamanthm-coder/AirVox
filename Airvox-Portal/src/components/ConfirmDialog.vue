<script setup lang="ts">
import { nextTick, onBeforeUnmount, onMounted, ref } from "vue";

withDefaults(
  defineProps<{ title: string; message: string; confirmLabel: string; busy?: boolean; busyLabel?: string }>(),
  { busyLabel: "Signing out…" },
);
const emit = defineEmits<{ confirm: []; cancel: [] }>();

const cancelButton = ref<HTMLButtonElement | null>(null);
// Focus goes back where it was when the dialog closes.
const returnFocus = document.activeElement as HTMLElement | null;

function onKey(event: KeyboardEvent) {
  if (event.key === "Escape") emit("cancel");
}

onMounted(async () => {
  document.addEventListener("keydown", onKey);
  await nextTick();
  cancelButton.value?.focus();
});

onBeforeUnmount(() => {
  document.removeEventListener("keydown", onKey);
  returnFocus?.focus();
});
</script>

<template>
  <div class="dialog-backdrop" @click.self="emit('cancel')">
    <div class="dialog" role="alertdialog" aria-modal="true" aria-labelledby="dialog-title" aria-describedby="dialog-message">
      <h2 id="dialog-title">{{ title }}</h2>
      <p id="dialog-message" class="muted">{{ message }}</p>
      <div class="dialog-actions">
        <button ref="cancelButton" type="button" class="ghost" :disabled="busy" @click="emit('cancel')">Cancel</button>
        <button type="button" class="primary small" :disabled="busy" @click="emit('confirm')">
          {{ busy ? busyLabel : confirmLabel }}
        </button>
      </div>
    </div>
  </div>
</template>
