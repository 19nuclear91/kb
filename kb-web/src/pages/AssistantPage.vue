<script setup lang="ts">
import { conversationEnabled } from '../navigation';
import PageHeader from '../ui/PageHeader.vue';
import KbAlert from '../ui/KbAlert.vue';
import AssistantWorkspace from '../assistant/AssistantWorkspace.vue';

withDefaults(defineProps<{ enabled?: boolean }>(), { enabled: conversationEnabled() });
</script>

<template>
  <AssistantWorkspace v-if="enabled" />
  <template v-else><PageHeader title="AI 助手" description="基于当前可见资料的个人问答" />
    <section class="panel"><KbAlert type="info" title="会话功能暂未开放" description="你可以继续使用文档中心和知识搜索。" /></section></template>
</template>
