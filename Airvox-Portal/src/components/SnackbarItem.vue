<script setup lang="ts">
import { onBeforeUnmount, onMounted } from "vue";
import { SHOW_MS, type Snack } from "../snackbar";

const props = defineProps<{ snack: Snack }>();
const emit = defineEmits<{ dismiss: [] }>();

let timer: number | undefined;

// Hovering pauses the countdown; leaving starts it again.
function start() {
  stop();
  timer = window.setTimeout(() => emit("dismiss"), SHOW_MS[props.snack.kind]);
}

function stop() {
  if (timer !== undefined) window.clearTimeout(timer);
  timer = undefined;
}

onMounted(start);
onBeforeUnmount(stop);
</script>

<template>
  <div
    :class="['snackbar', `snackbar-${snack.kind}`]"
    :role="snack.kind === 'error' ? 'alert' : 'status'"
    @mouseenter="stop"
    @mouseleave="start"
  >
    <span class="snackbar-icon" aria-hidden="true">{{ snack.kind === "success" ? "✓" : "!" }}</span>
    <span class="snackbar-text">{{ snack.text }}</span>
    <button type="button" class="snackbar-close" aria-label="Dismiss" @click="emit('dismiss')">×</button>
  </div>
</template>
