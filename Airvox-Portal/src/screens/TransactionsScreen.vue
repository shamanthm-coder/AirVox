<script setup lang="ts">
import { onMounted, ref } from "vue";
import { type CreditTransaction, loadTransactions } from "../api/transactions";
import { formatNumber, timeAgo } from "../format";
import { snackbar } from "../snackbar";

const rows = ref<CreditTransaction[]>([]);
const consumed = ref(0);
const purchased = ref(0);
const loading = ref(false);
const error = ref<string | null>(null);

async function refresh(announce: boolean) {
  if (loading.value) return;
  loading.value = true;
  const result = await loadTransactions();
  loading.value = false;
  if (result.error) {
    error.value = result.error;
    snackbar.error(result.error);
    return;
  }
  error.value = null;
  rows.value = result.rows;
  consumed.value = result.consumed;
  purchased.value = result.purchased;
  if (announce) snackbar.success("Transactions updated.");
}

onMounted(() => refresh(false));

function kindLabel(kind: string): string {
  if (kind === "consume") return "Used";
  if (kind === "grant") return "Free credits";
  return "Payment";
}
</script>

<template>
  <div class="content">
    <div class="content-head">
      <p class="sample">Credits used on a channel, and credit packs added in the app.</p>
      <button type="button" class="ghost small" :disabled="loading" @click="refresh(true)">
        {{ loading ? "Refreshing…" : "Refresh" }}
      </button>
    </div>

    <div v-if="error && rows.length === 0" class="box empty" role="alert">
      <h2>Couldn't load transactions</h2>
      <p class="muted">{{ error }}</p>
      <button type="button" class="primary small retry" :disabled="loading" @click="refresh(false)">Try again</button>
    </div>

    <template v-else>
      <section class="stats" aria-label="Credit totals">
        <div class="tile">
          <span class="tile-label">Credits used</span>
          <span class="tile-value">{{ formatNumber(consumed) }}</span>
          <span class="tile-note">1 credit each minute on a channel</span>
        </div>
        <div class="tile">
          <span class="tile-label">Credits added</span>
          <span class="tile-value">{{ formatNumber(purchased) }}</span>
          <span class="tile-note">Packs published under Plans</span>
        </div>
      </section>

      <section class="box">
        <div class="table-wrap">
          <table>
            <thead>
              <tr><th>Rider</th><th>Type</th><th class="num">Credits</th><th>When</th></tr>
            </thead>
            <tbody>
              <tr v-for="row in rows" :key="row.id">
                <td class="strong">{{ row.rider }}</td>
                <td>{{ kindLabel(row.kind) }}</td>
                <td class="num">{{ formatNumber(row.credits) }}</td>
                <td class="muted-cell">{{ timeAgo(row.createdAt) }}</td>
              </tr>
              <tr v-if="rows.length === 0">
                <td colspan="4" class="muted-cell empty-row">No transactions yet. They appear when a rider uses a channel or adds credits.</td>
              </tr>
            </tbody>
          </table>
        </div>
      </section>
    </template>
  </div>
</template>
