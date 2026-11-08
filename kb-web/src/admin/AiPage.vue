<script setup lang="ts">
import { computed } from 'vue';
import Feedback from '../ui/Feedback.vue';
import KbAlert from '../ui/KbAlert.vue';
import { useSession } from '../SessionProvider';
import { useAdminAccess } from './access';
import type { AiKind } from './aiUsageApi';
import AiContent from './AiContent.vue';

const props = defineProps<{ kind: AiKind }>();
const auth = useSession(), access = useAdminAccess(), code = computed(() => props.kind === 'usage' ? 'ai:usage:read' : 'ai:audit:read');
</script>

<template><Feedback v-if="auth.state.phase !== 'ready' || access.status !== 'ready'" :loading="auth.state.phase === 'loading' || access.status === 'loading'" error="当前身份或 AI 读取资格未确认。" /><KbAlert v-else-if="!access.has(code)" type="info" title="当前账号没有此 AI 页面的读取资格。" /><AiContent v-else :key="`${auth.state.epoch}|${access.permissionFactsKey ?? code}|${kind}`" :kind="kind" /></template>
