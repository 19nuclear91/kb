<script setup lang="ts">
import { computed } from 'vue';
import { useRoute } from 'vue-router';
import KbAlert from '../ui/KbAlert.vue';
import { useSession } from '../SessionProvider';
import { useReviewScope } from '../reviews/lifecycle';
import { validTarget } from './spaceUi';
import DocumentPanel from './DocumentPanel.vue';

const route = useRoute(), auth = useSession(), scope = useReviewScope(), id = computed(() => String(route.params.documentId ?? ''));
</script>

<template>
  <KbAlert v-if="!validTarget(id)" type="info" title="目标不可访问" /><DocumentPanel v-else-if="scope.active" :key="`${auth.state.epoch}:${scope.key}:${id}`" :id="id" />
</template>
