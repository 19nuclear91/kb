<script setup lang="ts">
import { onScopeDispose, watch } from 'vue';
import type { Session } from './session';
import { useSessionStore } from './stores/session';

const props = withDefaults(defineProps<{ session: Session; handoff?: boolean }>(), { handoff: false });
const store = useSessionStore();
store.bind(props.session);
onScopeDispose(() => store.release());

watch(() => [props.session, props.handoff] as const, ([session, handoff], _, onCleanup) => {
  store.bind(session);
  if (!handoff) {
    void session.refresh();
  }
  const focus = () => {
    if (document.visibilityState !== 'hidden') {
      void session.refresh();
    }
  };
  const visibility = () => {
    if (document.visibilityState === 'hidden') {
      session.suspend();
    } else {
      focus();
    }
  };
  window.addEventListener('focus', focus);
  document.addEventListener('visibilitychange', visibility);
  onCleanup(() => {
    window.removeEventListener('focus', focus);
    document.removeEventListener('visibilitychange', visibility);
    if (!handoff) {
      session.suspend();
    }
  });
}, { immediate: true });
</script>

<template>
  <slot />
</template>
