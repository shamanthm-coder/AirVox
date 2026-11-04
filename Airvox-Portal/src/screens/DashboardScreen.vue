<script setup lang="ts">
import { computed, ref } from "vue";
import { dashboard } from "../api/dashboard";
import ConfirmDialog from "../components/ConfirmDialog.vue";
import CreditPlansScreen from "./CreditPlansScreen.vue";
import OverviewScreen from "./OverviewScreen.vue";
import ReportsScreen from "./ReportsScreen.vue";
import TransactionsScreen from "./TransactionsScreen.vue";
import UsersScreen from "./UsersScreen.vue";
import type { StaffMember } from "../supabase";

const props = defineProps<{
  staff: StaffMember;
  /** Signs out; resolves when done. */
  onSignOut: () => Promise<void>;
}>();

const confirming = ref(false);
const signingOut = ref(false);

async function signOut() {
  signingOut.value = true;
  await props.onSignOut();
}

const NAV = [
  { group: "Operations", items: ["Dashboard", "Users", "Channels", "SOS incidents"] },
  { group: "Business", items: ["Wallet & payments", "Plans", "Reports"] },
  { group: "System", items: ["App releases", "Staff & roles", "Settings"] },
];

const section = ref("Dashboard");
const initial = computed(() => props.staff.email.charAt(0).toUpperCase());
const roleLabel = computed(() => props.staff.role.charAt(0).toUpperCase() + props.staff.role.slice(1));
</script>

<template>
  <div class="shell">
    <aside class="sidebar">
      <div class="sidebar-brand">
        <img src="/airvox-logo.png" alt="" />
        <span>Airvox <span class="brand-tag">Admin</span></span>
      </div>
      <nav class="nav" aria-label="Admin">
        <div v-for="{ group, items } in NAV" :key="group" class="nav-group">
          <p class="nav-heading">{{ group }}</p>
          <button
            v-for="item in items"
            :key="item"
            type="button"
            :class="['nav-item', { active: item === section }]"
            :aria-current="item === section ? 'page' : undefined"
            @click="section = item"
          >
            {{ item }}
            <span v-if="item === 'SOS incidents' && dashboard.data?.openSos" class="badge">{{ dashboard.data.openSos }}</span>
          </button>
        </div>
      </nav>
    </aside>

    <div class="main">
      <header class="topbar">
        <div>
          <p class="eyebrow">AIRVOX BUSINESS MANAGEMENT</p>
          <h1>{{ section }}</h1>
        </div>
        <div class="account">
          <span class="avatar" aria-hidden="true">{{ initial }}</span>
          <span class="account-who">
            <span class="account-email">{{ staff.email }}</span>
            <span class="account-role">{{ roleLabel }}</span>
          </span>
          <button type="button" class="ghost" @click="confirming = true">Sign out</button>
        </div>
      </header>

      <OverviewScreen v-if="section === 'Dashboard'" @open="section = $event" />
      <UsersScreen v-else-if="section === 'Users'" />
      <TransactionsScreen v-else-if="section === 'Wallet & payments'" />
      <CreditPlansScreen v-else-if="section === 'Plans'" />
      <ReportsScreen v-else-if="section === 'Reports'" />
      <div v-else class="content">
        <div class="box empty">
          <h2>{{ section }}</h2>
          <p class="muted">This screen comes next.</p>
        </div>
      </div>
    </div>

    <ConfirmDialog
      v-if="confirming"
      title="Sign out?"
      :message="`You'll need to sign in again to use the portal. Signed in as ${staff.email}.`"
      confirm-label="Sign out"
      :busy="signingOut"
      @confirm="signOut"
      @cancel="confirming = false"
    />
  </div>
</template>
