<script setup lang="ts">
import { onMounted, ref } from "vue";
import { type CreditPlan, deletePlan, loadPlans, savePlan } from "../api/plans";
import ConfirmDialog from "../components/ConfirmDialog.vue";
import WelcomeCreditsForm from "../components/WelcomeCreditsForm.vue";
import { formatNumber, formatRupees } from "../format";
import { snackbar } from "../snackbar";

const plans = ref<CreditPlan[]>([]);
const loading = ref(false);
const saving = ref(false);
const error = ref<string | null>(null);
const name = ref("");
const credits = ref("");
const rupees = ref("");
const draft = ref<{ id: number; name: string; credits: string; rupees: string } | null>(null);
const pending = ref<{ id?: number; name: string; credits: number; rupees: number; active: boolean } | null>(null);
const removing = ref<CreditPlan | null>(null);

async function refresh(announce: boolean) {
  if (loading.value) return;
  loading.value = true;
  const result = await loadPlans();
  loading.value = false;
  if (result.error) {
    error.value = result.error;
    snackbar.error(result.error);
    return;
  }
  error.value = null;
  plans.value = result.plans;
  if (announce) snackbar.success("Plans updated.");
}

onMounted(() => refresh(false));

function startEdit(plan: CreditPlan) {
  draft.value = { id: plan.id, name: plan.name, credits: String(plan.credits), rupees: String(plan.rupees) };
}

function cancelEdit() {
  draft.value = null;
}

function checkedPlan(label: string, creditsText: string, rupeesText: string): { name: string; credits: number; rupees: number } | null {
  const trimmed = label.trim();
  const amount = Number(creditsText);
  const price = Number(rupeesText);
  if (!trimmed) {
    snackbar.error("Enter a plan name.");
    return null;
  }
  if (!Number.isInteger(amount) || amount < 1 || amount > 10000) {
    snackbar.error("Credits must be a whole number from 1 to 10,000.");
    return null;
  }
  if (!Number.isInteger(price) || price < 1 || price > 100000) {
    snackbar.error("Rupees must be a whole number from 1 to 1,00,000.");
    return null;
  }
  return { name: trimmed, credits: amount, rupees: price };
}

function askAdd() {
  const plan = checkedPlan(name.value, credits.value, rupees.value);
  if (!plan) return;
  pending.value = { ...plan, active: true };
}

function askRowSave() {
  const row = draft.value;
  if (!row) return;
  const plan = checkedPlan(row.name, row.credits, row.rupees);
  if (!plan) return;
  const current = plans.value.find((item) => item.id === row.id);
  pending.value = { id: row.id, ...plan, active: current?.active ?? true };
}

async function confirmSave() {
  const plan = pending.value;
  if (!plan) return;
  saving.value = true;
  const message = await savePlan(plan);
  saving.value = false;
  if (message) {
    snackbar.error(message);
    return;
  }
  pending.value = null;
  if (plan.id) draft.value = null;
  else {
    name.value = "";
    credits.value = "";
    rupees.value = "";
  }
  snackbar.success(plan.id ? "Plan updated. The phone will show it on refresh." : "Plan added. The phone will show it on refresh.");
  await refresh(false);
}

async function setActive(plan: CreditPlan, active: boolean) {
  const message = await savePlan({ id: plan.id, name: plan.name, credits: plan.credits, rupees: plan.rupees, active });
  if (message) {
    snackbar.error(message);
    return;
  }
  plan.active = active;
  snackbar.success(active ? `${plan.name} is on the phone.` : `${plan.name} is hidden from the phone.`);
}

async function confirmDelete() {
  const plan = removing.value;
  if (!plan) return;
  saving.value = true;
  const message = await deletePlan(plan.id);
  saving.value = false;
  removing.value = null;
  if (message) {
    snackbar.error(message);
    return;
  }
  if (draft.value?.id === plan.id) draft.value = null;
  snackbar.success(`${plan.name} deleted.`);
  await refresh(false);
}
</script>

<template>
  <div class="content">
    <div class="content-head">
      <p class="sample">Plans created here are the credit packs on the phone. Hidden plans stay off the phone.</p>
      <button type="button" class="ghost small" :disabled="loading" @click="refresh(true)">
        {{ loading ? "Refreshing…" : "Refresh" }}
      </button>
    </div>

    <div v-if="error && plans.length === 0" class="box empty" role="alert">
      <h2>Couldn't load plans</h2>
      <p class="muted">{{ error }}</p>
      <button type="button" class="primary small retry" :disabled="loading" @click="refresh(false)">Try again</button>
    </div>

    <template v-else>
      <WelcomeCreditsForm />

      <form class="toolbar" @submit.prevent="askAdd">
        <input v-model="name" class="search" type="text" maxlength="40" placeholder="Plan name" aria-label="Plan name" />
        <input v-model="credits" class="search" type="number" min="1" max="10000" placeholder="Credits" aria-label="Credits" />
        <input v-model="rupees" class="search" type="number" min="1" max="100000" placeholder="Rupees" aria-label="Rupees" />
        <button type="submit" class="primary small" :disabled="saving">Add plan</button>
      </form>

      <section class="box">
        <div class="table-wrap">
          <table class="plans-table">
            <colgroup>
              <col class="col-plan" />
              <col class="col-phone" />
              <col class="col-status" />
              <col class="col-actions" />
            </colgroup>
            <thead>
              <tr>
                <th>Plan</th><th>On the phone</th><th>Status</th>
                <th><span class="sr-only">Actions</span></th>
              </tr>
            </thead>
            <tbody>
              <tr v-for="plan in plans" :key="plan.id" :class="{ 'row-editing': draft?.id === plan.id }">
                <template v-if="draft?.id === plan.id">
                  <td>
                    <input v-model="draft.name" class="cell-input" type="text" maxlength="40" aria-label="Plan name" placeholder="Plan name" />
                  </td>
                  <td>
                    <div class="edit-pack">
                      <label class="input-affix">
                        <input v-model="draft.credits" type="number" min="1" max="10000" inputmode="numeric" aria-label="Credits" />
                        <span>credits</span>
                      </label>
                      <label class="input-affix">
                        <span class="prefix">₹</span>
                        <input v-model="draft.rupees" type="number" min="1" max="100000" inputmode="numeric" aria-label="Rupees" />
                      </label>
                    </div>
                  </td>
                  <td><span :class="['seen', { online: plan.active }]">{{ plan.active ? "Shown" : "Hidden" }}</span></td>
                  <td class="actions">
                    <button type="button" class="primary small" :disabled="saving" @click="askRowSave">Save</button>
                    <button type="button" class="ghost small" :disabled="saving" @click="cancelEdit">Cancel</button>
                  </td>
                </template>
                <template v-else>
                  <td class="strong">{{ plan.name }}</td>
                  <td>{{ formatNumber(plan.credits) }} credits · {{ formatRupees(plan.rupees) }}</td>
                  <td><span :class="['seen', { online: plan.active }]">{{ plan.active ? "Shown" : "Hidden" }}</span></td>
                  <td class="actions">
                    <button type="button" class="ghost small" @click="startEdit(plan)">Edit</button>
                    <button type="button" class="ghost small" @click="setActive(plan, !plan.active)">{{ plan.active ? "Hide" : "Show" }}</button>
                    <button type="button" class="ghost small danger" @click="removing = plan">Delete</button>
                  </td>
                </template>
              </tr>
              <tr v-if="plans.length === 0">
                <td colspan="4" class="muted-cell empty-row">No plans yet. Add one and it will show on the phone.</td>
              </tr>
            </tbody>
          </table>
        </div>
      </section>
    </template>

    <ConfirmDialog
      v-if="pending"
      :title="pending.id ? 'Save this plan?' : 'Add this plan?'"
      :message="pending.id
        ? `${pending.name} will show as ${formatNumber(pending.credits)} credits · ${formatRupees(pending.rupees)} on the phone.`
        : `${pending.name} will be added as ${formatNumber(pending.credits)} credits · ${formatRupees(pending.rupees)}.`"
      confirm-label="Save"
      busy-label="Saving…"
      :busy="saving"
      @confirm="confirmSave"
      @cancel="pending = null"
    />

    <ConfirmDialog
      v-if="removing"
      title="Delete this plan?"
      :message="`${removing.name} will disappear from the phone. Riders who already added those credits keep them.`"
      confirm-label="Delete"
      busy-label="Deleting…"
      :busy="saving"
      @confirm="confirmDelete"
      @cancel="removing = null"
    />
  </div>
</template>
