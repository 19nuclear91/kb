<script setup lang="ts">
import Feedback from '../ui/Feedback.vue';
import KbAlert from '../ui/KbAlert.vue';
import { useSession } from '../SessionProvider';
import { perm, useAdminAccess } from './access';
import EmbeddingContent from './EmbeddingContent.vue';

const auth = useSession(), access = useAdminAccess();
</script>

<template>
  <Feedback v-if="access.status !== 'ready' || auth.state.phase !== 'ready'" :loading="access.status === 'loading'" error="当前运维资格未确认。" /><KbAlert v-else-if="!access.has(perm.embeddingRead)" type="info" title="当前账号没有向量管理读取资格。" />
  <EmbeddingContent v-else :key="`${auth.state.epoch}|${access.factsKey ?? ''}|${access.has(perm.embeddingRebuild)}`" />
</template>
