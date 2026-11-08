<script setup lang="ts">
import { useSession } from '../SessionProvider';
import ConversationContent from './ConversationContent.vue';

defineProps<{ conversationId: string }>();
const emit = defineEmits<{ deleted: [] }>();
const auth = useSession();
</script>

<template>
  <ConversationContent v-if="auth.state.phase === 'ready'" :key="`${conversationId}:${auth.state.epoch}`" :conversation-id="conversationId" @deleted="emit('deleted')" />
</template>
