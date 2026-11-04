<script setup lang="ts">
import { dismissSnack, snacks } from "../snackbar";
import SnackbarItem from "./SnackbarItem.vue";
</script>

<template>
  <div class="snackbars">
    <SnackbarItem v-for="snack in snacks" :key="snack.id" :snack="snack" @dismiss="dismissSnack(snack.id)" />
  </div>
</template>
