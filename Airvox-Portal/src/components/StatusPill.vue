<script setup lang="ts">
import type { UserStatus } from "../sampleData";

defineProps<{ status: UserStatus }>();
</script>

<template>
  <span :class="['pill', `status-${status.toLowerCase()}`]">{{ status }}</span>
</template>
