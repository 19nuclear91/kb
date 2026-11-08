<script setup lang="ts">
import { computed } from 'vue';
import { useRoute } from 'vue-router';
import KbAlert from '../ui/KbAlert.vue';
import { useSession } from '../SessionProvider';
import { validTarget } from './spaceUi';
import SpaceDetail from './SpaceDetail.vue';

const route = useRoute(), auth = useSession(), id = computed(() => String(route.params.spaceId ?? ''));
</script>

<template>
  <SpaceDetail v-if="validTarget(id)" :key="`${auth.state.epoch}:${id}`" :id="id" /><KbAlert v-else type="info" title="目标不可访问" />
</template>
