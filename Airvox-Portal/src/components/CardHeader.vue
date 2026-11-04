<script setup lang="ts">
defineProps<{ title: string; subtitle: string }>();
</script>

<template>
  <header class="card-header">
    <img src="/airvox-logo.png" alt="Airvox" class="card-logo" />
    <p class="eyebrow">AIRVOX ADMIN</p>
    <h1>{{ title }}</h1>
    <p class="muted">{{ subtitle }}</p>
  </header>
</template>
