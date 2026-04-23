<template>
  <div class="shell">
    <AppSidebar />
    <div class="shell-main">
      <AppHeader />
      <main class="shell-content">
        <router-view />
      </main>
    </div>
  </div>
</template>

<script setup>
import AppHeader from './AppHeader.vue'
import AppSidebar from './AppSidebar.vue'
</script>

<style scoped>
.shell {
  min-height: 100vh;
  display: grid;
  grid-template-columns: 290px minmax(0, 1fr);
}

.shell-main {
  min-width: 0;
  display: grid;
  grid-template-rows: auto 1fr;
}

.shell-content {
  min-width: 0;
}

@media (max-width: 1080px) {
  .shell {
    grid-template-columns: 1fr;
  }
}
</style>
