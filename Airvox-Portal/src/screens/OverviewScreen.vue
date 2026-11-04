<script setup lang="ts">
import { computed, onMounted, onUnmounted } from "vue";
import StatusPill from "../components/StatusPill.vue";
import { dashboard, loadDashboard } from "../api/dashboard";
import { formatDate, formatDay, formatNumber, formatRupees, monthName, timeAgo } from "../format";
import { snackbar } from "../snackbar";

const emit = defineEmits<{ open: [section: string] }>();

async function refresh(announce: boolean) {
  const ok = await loadDashboard();
  if (!ok && dashboard.error) snackbar.error(dashboard.error);
  else if (ok && announce) snackbar.success("Dashboard updated.");
}

let refreshTimer = 0;
onMounted(() => {
  refresh(false);
  refreshTimer = window.setInterval(() => {
    if (!dashboard.loading) loadDashboard();
  }, 30_000);
});
onUnmounted(() => window.clearInterval(refreshTimer));

const d = computed(() => dashboard.data);

const kpis = computed(() => {
  const s = d.value;
  if (!s) return [];
  const share = s.totalUsers ? Math.round((s.activeToday / s.totalUsers) * 100) : 0;
  let topupNote = "No top-ups last month";
  if (s.topupsLastMonth > 0) {
    const change = Math.round(((s.topupsThisMonth - s.topupsLastMonth) / s.topupsLastMonth) * 100);
    topupNote = `${change >= 0 ? "+" : ""}${change}% on last month`;
  }
  return [
    { label: "Logged in", value: formatNumber(s.loggedIn ?? s.onlineNow ?? 0), note: "Signed in, app open in the last 5 minutes" },
    { label: "Total users", value: formatNumber(s.totalUsers), note: `+${formatNumber(s.newUsers7d)} in the last 7 days` },
    { label: "Active today", value: formatNumber(s.activeToday), note: `${share}% of all users` },
    { label: "Live channels", value: formatNumber(s.liveChannels), note: `${s.liveOffline} offline, ${s.liveInternet} internet` },
    {
      label: "Open SOS incidents",
      value: formatNumber(s.openSos),
      note: s.oldestOpenSosAt ? `Oldest ${timeAgo(s.oldestOpenSosAt).toLowerCase()}` : "None open",
      alert: s.openSos > 0,
    },
    { label: `Wallet top-ups (${monthName()})`, value: formatRupees(s.topupsThisMonth), note: topupNote },
  ];
});

const chart = computed(() => {
  const days = d.value?.dailyActive ?? [];
  const peak = Math.max(1, ...days.map((x) => x.count));
  const label = days.length
    ? `Daily active users over 14 days, from ${days[0].count} to ${days[days.length - 1].count}, peak ${peak}`
    : "No activity yet";
  return { days, peak, label };
});

const attention = computed(() => {
  const s = d.value;
  if (!s) return [];
  return [
    { label: "User reports to review", count: s.openReports },
    { label: "Failed wallet payments (7 days)", count: s.failedPayments },
    { label: "Accounts waiting for approval", count: s.pendingAccounts },
    ...s.rollouts.map((r) => ({ label: `${r.platform} ${r.version} rollout at ${r.percent}%`, count: null })),
  ];
});

const split = computed(() => {
  const s = d.value;
  const total = s ? s.androidUsers + s.iosUsers : 0;
  if (!s || total === 0) return null;
  const android = Math.round((s.androidUsers / total) * 100);
  return { android, ios: 100 - android };
});
</script>

<template>
  <div class="content">
    <div class="content-head">
      <p class="sample">
        <template v-if="d">Updated {{ timeAgo(d.generatedAt).toLowerCase() }} · Live from Supabase</template>
        <template v-else-if="dashboard.loading">Loading the dashboard…</template>
      </p>
      <button type="button" class="ghost small" :disabled="dashboard.loading" @click="refresh(true)">
        {{ dashboard.loading ? "Refreshing…" : "Refresh" }}
      </button>
    </div>

    <div v-if="!d && dashboard.error" class="box empty" role="alert">
      <h2>Couldn't load the dashboard</h2>
      <p class="muted">{{ dashboard.error }}</p>
      <button type="button" class="primary small retry" :disabled="dashboard.loading" @click="refresh(false)">Try again</button>
    </div>

    <template v-else>
      <section class="stats" aria-label="Key numbers" :aria-busy="!d">
        <template v-if="d">
          <div v-for="k in kpis" :key="k.label" :class="['tile', { 'tile-alert': k.alert }]">
            <span class="tile-label">{{ k.label }}</span>
            <span class="tile-value">{{ k.value }}</span>
            <span class="tile-note">{{ k.note }}</span>
          </div>
        </template>
        <div v-for="n in 6" v-else :key="n" class="tile skeleton" aria-hidden="true">
          <span class="skeleton-line short" />
          <span class="skeleton-line tall" />
          <span class="skeleton-line" />
        </div>
      </section>

      <div v-if="d" class="grid">
        <section class="box">
          <div class="box-head">
            <h2>Daily active users</h2>
            <span class="muted-cell">Last 14 days</span>
          </div>
          <div class="bars" role="img" :aria-label="chart.label">
            <span
              v-for="(day, i) in chart.days"
              :key="day.day"
              :class="['bar', { today: i === chart.days.length - 1 }]"
              :style="{ height: `${(day.count / chart.peak) * 100}%` }"
              :title="`${formatDay(day.day)}: ${formatNumber(day.count)} users`"
            />
          </div>
          <div v-if="chart.days.length" class="bars-axis muted-cell">
            <span>{{ formatDay(chart.days[0].day) }}</span><span>Today</span>
          </div>
        </section>

        <section class="box">
          <h2>Needs attention</h2>
          <ul class="list">
            <li v-for="a in attention" :key="a.label">
              <span class="list-main">{{ a.label }}</span>
              <span v-if="a.count !== null" :class="['count', { 'count-zero': a.count === 0 }]">{{ a.count }}</span>
            </li>
          </ul>
          <div class="split">
            <template v-if="split">
              <div class="split-head muted-cell">
                <span>Android {{ split.android }}%</span><span>iOS {{ split.ios }}%</span>
              </div>
              <div class="split-bar" role="img" :aria-label="`Android ${split.android}%, iOS ${split.ios}%`">
                <span :style="{ width: `${split.android}%` }" />
              </div>
            </template>
            <p v-else class="muted-cell">No riders yet, so no Android / iOS split.</p>
          </div>
        </section>

        <section class="box wide">
          <div class="box-head">
            <h2>Logged in now</h2>
            <button type="button" class="link-button" @click="emit('open', 'Users')">Manage users</button>
          </div>
          <div class="table-wrap">
            <table>
              <thead><tr><th>Rider</th><th>Platform</th><th>Last check-in</th><th>Session</th></tr></thead>
              <tbody>
                <tr v-for="u in d.loggedInUsers ?? []" :key="u.id">
                  <td class="strong">{{ u.name }}</td>
                  <td>{{ u.platform ?? "—" }}</td>
                  <td class="muted-cell">{{ timeAgo(u.lastActiveAt) }}</td>
                  <td><span class="seen online">Logged in</span></td>
                </tr>
                <tr v-if="(d.loggedInUsers ?? []).length === 0">
                  <td colspan="4" class="muted-cell empty-row">Nobody is logged in right now.</td>
                </tr>
              </tbody>
            </table>
          </div>
        </section>

        <section class="box wide">
          <div class="box-head">
            <h2>SOS incidents</h2>
            <button type="button" class="link-button" @click="emit('open', 'SOS incidents')">View all</button>
          </div>
          <div class="table-wrap">
            <table>
              <thead><tr><th>ID</th><th>Rider</th><th>Location</th><th>Raised</th><th>Status</th></tr></thead>
              <tbody>
                <tr v-for="s in d.incidents" :key="s.id">
                  <td class="muted-cell">SOS-{{ s.id }}</td>
                  <td class="strong">{{ s.rider }}</td>
                  <td>{{ s.location ?? "Unknown" }}</td>
                  <td class="muted-cell">{{ timeAgo(s.raisedAt) }}</td>
                  <td><span :class="['pill', { 'pill-red': s.status === 'Open' }]">{{ s.status }}</span></td>
                </tr>
                <tr v-if="d.incidents.length === 0">
                  <td colspan="5" class="muted-cell empty-row">No SOS incidents yet.</td>
                </tr>
              </tbody>
            </table>
          </div>
        </section>

        <section class="box wide">
          <div class="box-head">
            <h2>Newest users</h2>
            <button type="button" class="link-button" @click="emit('open', 'Users')">Manage users</button>
          </div>
          <div class="table-wrap">
            <table>
              <thead><tr><th>User</th><th>Platform</th><th>Joined</th><th>Status</th></tr></thead>
              <tbody>
                <tr v-for="u in d.newestUsers" :key="u.id">
                  <td class="strong">{{ u.name }}</td>
                  <td>{{ u.platform }}</td>
                  <td class="muted-cell">{{ formatDate(u.joinedAt) }}</td>
                  <td><StatusPill :status="u.status" /></td>
                </tr>
                <tr v-if="d.newestUsers.length === 0">
                  <td colspan="4" class="muted-cell empty-row">No riders yet.</td>
                </tr>
              </tbody>
            </table>
          </div>
        </section>
      </div>
    </template>
  </div>
</template>
