<script setup lang="ts">
import { navigation } from '../navigation';
import NavItem from './NavItem.vue';
</script>

<template>
  <nav aria-label="主要导航">
    <NavItem v-for="item in navigation" :key="item.path" :to="item.path"><span class="nav-mark">{{ item.mark }}</span>{{ item.label }}<slot name="extra" :path="item.path" /></NavItem>
  </nav>
</template>
