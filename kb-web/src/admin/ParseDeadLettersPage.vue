<script setup lang="ts">
import Feedback from '../ui/Feedback.vue';
import KbAlert from '../ui/KbAlert.vue';
import { useSession } from '../SessionProvider';
import { perm, useAdminAccess } from './access';
import DeadLettersContent from './DeadLettersContent.vue';

const auth = useSession(), access = useAdminAccess();
</script>

<template>
  <Feedback v-if="access.status !== 'ready' || auth.state.phase !== 'ready'" :loading="access.status === 'loading'" error="当前运维资格未确认。" /><KbAlert v-else-if="!access.has(perm.deadLetterRead)" type="info" title="当前账号没有解析死信读取资格。" />
  <DeadLettersContent v-else :key="`${auth.state.epoch}|${access.factsKey ?? ''}|${access.has(perm.deadLetterReplay)}`" />
</template>
