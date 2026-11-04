<script setup lang="ts">
import { onMounted, ref } from "vue";
import { type CreditReport, type ReportPeriod, loadReport } from "../api/reports";
import { formatNumber, timeAgo } from "../format";
import { snackbar } from "../snackbar";

const PERIODS: { id: ReportPeriod; label: string }[] = [
  { id: "today", label: "Today" },
  { id: "7", label: "7 days" },
  { id: "30", label: "30 days" },
];

const period = ref<ReportPeriod>("7");
const report = ref<CreditReport | null>(null);
const loading = ref(false);
const error = ref<string | null>(null);

async function refresh(announce: boolean) {
  if (loading.value) return;
  loading.value = true;
  const result = await loadReport(period.value);
  loading.value = false;
  if (result.error || !result.report) {
    error.value = result.error;
    snackbar.error(result.error ?? "Couldn't load the report.");
    return;
  }
  error.value = null;
  report.value = result.report;
  if (announce) snackbar.success("Report updated.");
}

function choose(next: ReportPeriod) {
  if (period.value === next) return;
  period.value = next;
  refresh(false);
}

onMounted(() => refresh(false));
</script>

<template>
  <div class="content">
    <div class="content-head">
      <p class="sample">Credits and riders for the selected period. Credits left are the balances on the phones right now.</p>
      <button type="button" class="ghost small" :disabled="loading" @click="refresh(true)">
        {{ loading ? "Refreshing…" : "Refresh" }}
      </button>
    </div>

    <div class="toolbar">
      <div class="segmented" role="group" aria-label="Report period">
        <button
          v-for="item in PERIODS"
          :key="item.id"
          type="button"
          :class="['seg', { active: item.id === period }]"
          :aria-pressed="item.id === period"
          @click="choose(item.id)"
        >
          {{ item.label }}
        </button>
      </div>
    </div>

    <div v-if="error && !report" class="box empty" role="alert">
      <h2>Couldn't load the report</h2>
      <p class="muted">{{ error }}</p>
      <button type="button" class="primary small retry" :disabled="loading" @click="refresh(false)">Try again</button>
    </div>

    <template v-else-if="report">
      <section class="stats" aria-label="Report totals">
        <div class="tile">
          <span class="tile-label">New riders</span>
          <span class="tile-value">{{ formatNumber(report.newRiders) }}</span>
          <span class="tile-note">Joined in this period</span>
        </div>
        <div class="tile">
          <span class="tile-label">Opened the app</span>
          <span class="tile-value">{{ formatNumber(report.opened) }}</span>
          <span class="tile-note">Checked in on at least one day</span>
        </div>
        <div class="tile">
          <span class="tile-label">Credits used</span>
          <span class="tile-value">{{ formatNumber(report.used) }}</span>
          <span class="tile-note">Spent on a channel</span>
        </div>
        <div class="tile">
          <span class="tile-label">Credits bought</span>
          <span class="tile-value">{{ formatNumber(report.bought) }}</span>
          <span class="tile-note">Packs from Plans</span>
        </div>
        <div class="tile">
          <span class="tile-label">Free credits</span>
          <span class="tile-value">{{ formatNumber(report.free) }}</span>
          <span class="tile-note">Given on a first sign-in</span>
        </div>
        <div class="tile">
          <span class="tile-label">Credits left</span>
          <span class="tile-value">{{ formatNumber(report.left) }}</span>
          <span class="tile-note">On phones right now</span>
        </div>
      </section>

      <section class="box">
        <div class="table-wrap">
          <table>
            <thead>
              <tr>
                <th>Rider</th>
                <th class="num">Credits left</th>
                <th class="num">Used</th>
                <th class="num">Bought</th>
                <th class="num">Free credits</th>
                <th>Last active</th>
              </tr>
            </thead>
            <tbody>
              <tr v-for="rider in report.riders" :key="rider.id">
                <td class="strong">{{ rider.name }}</td>
                <td class="num">{{ formatNumber(rider.left) }}</td>
                <td class="num">{{ formatNumber(rider.used) }}</td>
                <td class="num">{{ formatNumber(rider.bought) }}</td>
                <td class="num">{{ formatNumber(rider.free) }}</td>
                <td class="muted-cell">{{ rider.lastActiveAt ? timeAgo(rider.lastActiveAt) : "Never" }}</td>
              </tr>
              <tr v-if="report.riders.length === 0">
                <td colspan="6" class="muted-cell empty-row">No riders yet. They appear here after someone signs in to the app.</td>
              </tr>
            </tbody>
          </table>
        </div>
      </section>
    </template>
  </div>
</template>
