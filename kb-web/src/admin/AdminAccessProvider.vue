<script setup lang="ts">
import { onScopeDispose } from 'vue';
import { useAdminAccessStore } from '../stores/adminAccess';

const props = withDefaults(defineProps<{ loadSpaces?: boolean }>(), { loadSpaces: true });
const store = useAdminAccessStore();
onScopeDispose(store.attach(() => ({ loadSpaces: props.loadSpaces })));
</script>

<template>
  <slot />
</template>
