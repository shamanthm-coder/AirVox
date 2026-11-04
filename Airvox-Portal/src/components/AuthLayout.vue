<script setup lang="ts">
// The brand panel shared by sign-in and the password screens; the card goes in the slot.
</script>

<template>
  <main class="page">
    <section class="brand" aria-hidden="true">
      <div class="brand-inner">
        <img src="/airvox-logo.png" alt="" class="brand-logo" />
        <h2>Airvox Business Management</h2>
        <p>The back office for the Airvox team. Riders use the app; this console is for staff.</p>
        <ul class="brand-points">
          <li>Manage users, accounts and wallets</li>
          <li>Monitor live channels and SOS incidents</li>
          <li>Reports, app releases and staff roles</li>
        </ul>
      </div>
    </section>
    <section class="panel">
      <slot />
    </section>
  </main>
</template>
