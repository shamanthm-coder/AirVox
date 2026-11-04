<script setup lang="ts">
import { computed, onMounted, onUnmounted, ref } from "vue";
import { type ManagedUser, loadUsers, updateUserStatus } from "../api/users";
import StatusPill from "../components/StatusPill.vue";
import { formatDate, formatNumber, timeAgo } from "../format";
import type { UserStatus } from "../sampleData";
import { snackbar } from "../snackbar";

const FILTERS = ["All", "Logged in", "Active", "Pending", "Suspended"] as const;

const users = ref<ManagedUser[]>([]);
const loading = ref(false);
const error = ref<string | null>(null);
const query = ref("");
const filter = ref<(typeof FILTERS)[number]>("All");
const savingId = ref<string | null>(null);

const loggedInCount = computed(() => users.value.filter((u) => u.loggedIn).length);

const shown = computed(() => {
  const q = query.value.trim().toLowerCase();
  const digits = q.replace(/\s/g, "");
  return users.value.filter((u) => {
    const statusOk = filter.value === "All" || (filter.value === "Logged in" ? u.loggedIn : u.status === filter.value);
    const phone = (u.phone ?? "").replace(/\s/g, "");
    const textOk =
      q === "" ||
      u.name.toLowerCase().includes(q) ||
      (u.email ?? "").toLowerCase().includes(q) ||
      phone.includes(digits) ||
      u.id.toLowerCase().includes(q);
    return statusOk && textOk;
  });
});

async function refresh(announce: boolean) {
  if (loading.value) return;
  loading.value = true;
  const result = await loadUsers();
  loading.value = false;
  if (result.error) {
    error.value = result.error;
    snackbar.error(result.error);
    return;
  }
  error.value = null;
  users.value = result.users;
  if (announce) snackbar.success("Users updated.");
}

let refreshTimer = 0;
onMounted(() => {
  refresh(false);
  refreshTimer = window.setInterval(() => refresh(false), 30_000);
});
onUnmounted(() => window.clearInterval(refreshTimer));

async function setStatus(user: ManagedUser, status: UserStatus) {
  if (savingId.value) return;
  const previous = user.status;
  user.status = status;
  savingId.value = user.id;
  const message = await updateUserStatus(user.id, status);
  savingId.value = null;
  if (message) {
    user.status = previous;
    snackbar.error(message);
    return;
  }
  const done = status === "Suspended" ? "suspended" : previous === "Pending" ? "approved" : "restored";
  snackbar.success(`${user.name} ${done}.`);
}

function contact(user: ManagedUser): string {
  return user.phone || user.email || "—";
}
</script>

<template>
  <div class="content">
    <div class="content-head">
      <p class="sample">
        <template v-if="!error">{{ loggedInCount }} logged in · {{ users.length }} riders</template>
      </p>
      <button type="button" class="ghost small" :disabled="loading" @click="refresh(true)">
        {{ loading ? "Refreshing…" : "Refresh" }}
      </button>
    </div>

    <div v-if="error && users.length === 0" class="box empty" role="alert">
      <h2>Couldn't load users</h2>
      <p class="muted">{{ error }}</p>
      <button type="button" class="primary small retry" :disabled="loading" @click="refresh(false)">Try again</button>
    </div>

    <template v-else>
      <div class="toolbar">
        <input v-model="query" class="search" type="search" placeholder="Search by name, phone, email or user ID" aria-label="Search users" />
        <div class="segmented" role="group" aria-label="Filter users">
          <button
            v-for="f in FILTERS"
            :key="f"
            type="button"
            :class="['seg', { active: f === filter }]"
            :aria-pressed="f === filter"
            @click="filter = f"
          >
            {{ f }}
          </button>
        </div>
      </div>

      <section class="box">
        <div class="table-wrap">
          <table>
            <thead>
              <tr>
                <th>User</th><th>Contact</th><th>Platform</th><th>Joined</th><th>Last active</th><th>Session</th><th class="num">Credits</th><th>Status</th>
                <th><span class="sr-only">Actions</span></th>
              </tr>
            </thead>
            <tbody>
              <tr v-for="u in shown" :key="u.id">
                <td>
                  <span class="user-cell">
                    <span class="avatar small" aria-hidden="true">{{ u.name.charAt(0) }}</span>
                    <span class="list-main">
                      <span class="strong">{{ u.name }}</span>
                      <span class="muted-cell">{{ u.id }}</span>
                    </span>
                  </span>
                </td>
                <td>{{ contact(u) }}</td>
                <td>{{ u.platform ?? "—" }}</td>
                <td class="muted-cell">{{ formatDate(u.joinedAt) }}</td>
                <td class="muted-cell">{{ u.lastActiveAt ? timeAgo(u.lastActiveAt) : "Never" }}</td>
                <td><span :class="['seen', { online: u.loggedIn }]">{{ u.loggedIn ? "Logged in" : "Signed out" }}</span></td>
                <td class="num">{{ formatNumber(u.wallet) }}</td>
                <td><StatusPill :status="u.status" /></td>
                <td class="actions">
                  <button v-if="u.status === 'Pending'" type="button" class="ghost small" :disabled="savingId === u.id" @click="setStatus(u, 'Active')">Approve</button>
                  <button v-else-if="u.status === 'Active'" type="button" class="ghost small danger" :disabled="savingId === u.id" @click="setStatus(u, 'Suspended')">Suspend</button>
                  <button v-else type="button" class="ghost small" :disabled="savingId === u.id" @click="setStatus(u, 'Active')">Restore</button>
                </td>
              </tr>
              <tr v-if="shown.length === 0">
                <td colspan="9" class="muted-cell empty-row">
                  {{ users.length === 0 ? "No riders yet. They appear here when someone signs in to the Airvox app." : "No users match." }}
                </td>
              </tr>
            </tbody>
          </table>
        </div>
        <p class="table-foot muted-cell">Showing {{ shown.length }} of {{ users.length }} riders · {{ loggedInCount }} logged in</p>
      </section>
    </template>
  </div>
</template>
