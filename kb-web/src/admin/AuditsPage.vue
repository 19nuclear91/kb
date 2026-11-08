<script setup lang="ts">
import Feedback from '../ui/Feedback.vue';
import KbAlert from '../ui/KbAlert.vue';
import { useSession } from '../SessionProvider';
import { useAdminAccess } from './access';
import AuditsContent from './AuditsContent.vue';

const auth = useSession(), access = useAdminAccess();
</script>

<template>
  <Feedback v-if="auth.state.phase !== 'ready' || access.status !== 'ready'" :loading="auth.state.phase === 'loading' || access.status === 'loading'" error="当前身份或审计资格未确认。" /><KbAlert v-else-if="!access.has('audit:read')" type="info" title="当前账号没有审计读取资格。" /><AuditsContent v-else :key="`${auth.state.epoch}|${access.permissionFactsKey ?? 'audit:read'}`" />
</template>
