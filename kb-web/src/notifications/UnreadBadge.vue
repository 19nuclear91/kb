<script setup lang="ts">
import { computed } from 'vue';
import { useUnreadCount } from './unread';

const unread = useUnreadCount();
const shown = computed(() => {
  const state = unread.state;
  return state.phase === 'ready' && BigInt(state.count) > 99n ? '99+' : state.phase === 'ready' ? state.count : '';
});
</script>

<template>
  <span v-if="unread.state.phase === 'unavailable'" class="nav-badge muted-badge" aria-label="未读数待确认">?</span>
  <span v-else-if="unread.state.phase === 'ready' && unread.state.count !== '0'" class="nav-badge" :aria-label="`未读 ${unread.state.count} 条`">{{ shown }}</span>
</template>
