<script setup lang="ts">
import { ref } from "vue";

defineProps<{ placeholder: string; autocomplete: string; invalid?: boolean }>();
const value = defineModel<string>({ required: true });
const shown = ref(false);
</script>

<template>
  <span class="password">
    <input
      v-model="value"
      :type="shown ? 'text' : 'password'"
      :autocomplete="autocomplete"
      autocapitalize="none"
      :placeholder="placeholder"
      :aria-invalid="invalid || undefined"
    />
    <button type="button" class="reveal" :aria-label="shown ? 'Hide password' : 'Show password'" @click="shown = !shown">
      {{ shown ? "Hide" : "Show" }}
    </button>
  </span>
</template>
