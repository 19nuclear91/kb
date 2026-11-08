<script setup lang="ts">
import { computed } from 'vue';
import NavItem from '../routing/NavItem.vue';
import { adminNavigation, useAdminAccess } from './access';

const access = useAdminAccess();
const items = computed(() => adminNavigation.filter(item => item.visible(access)));
</script>

<template>
  <nav v-if="items.length" aria-label="管理导航" style="margin-top: 24px">
    <div class="muted" style="padding: 0 16px">管理</div>
    <NavItem v-for="item in items" :key="item.path" :to="item.path"><span class="nav-mark">{{ item.mark }}</span>{{ item.label }}</NavItem>
  </nav>
</template>
