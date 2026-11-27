<script setup lang="ts">
import { ElButton } from 'element-plus';
import KbAlert from '../ui/KbAlert.vue';
import type { useCommand } from './hooks';
defineProps<{ command: ReturnType<typeof useCommand> }>();
</script>
<template>
  <section v-if="command.blocked" aria-label="订阅请求结果" class="panel">
    <p v-if="command.status === 'sending'" role="status">正在提交或核对原请求…</p>
    <KbAlert v-if="command.message" type="info" :title="command.message" />
    <p v-if="command.receipt">原请求生效时间：{{ command.receipt.effectiveAt }}</p>
    <template v-if="command.status === 'unknown'">
      <ElButton @click="command.check">核对原回执</ElButton><ElButton @click="command.retry">重试同一请求</ElButton>
      <p>重新读取当前状态不能证明原请求的结果。放弃核对不会撤销可能已完成的操作。</p>
    </template>
    <ElButton v-if="command.status !== 'sending'" @click="command.abandon">{{ command.status === 'unknown' ? '放弃核对，重新确认' : '完成核对' }}</ElButton>
  </section>
</template>
