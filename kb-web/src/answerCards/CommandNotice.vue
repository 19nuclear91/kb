<script setup lang="ts">
import { ElButton } from 'element-plus';
import KbAlert from '../ui/KbAlert.vue';
import type { PendingCommand } from './commands';
defineProps<{ pending?: PendingCommand }>();
defineEmits<{ check: []; retry: []; abandon: []; refresh: [] }>();
</script>
<template>
  <section v-if="pending" class="panel" aria-label="原写入意图核对">
    <KbAlert type="warning" :title="pending.notice || (pending.phase === 'checking' ? '正在核对原请求…' : '正在提交原意图…')" />
    <p>关闭或重读页面不能证明写入失败。放弃后必须先读取服务器事实。</p>
    <template v-if="pending.phase === 'unknown'">
      <ElButton @click="$emit('check')">核对原请求</ElButton><ElButton @click="$emit('retry')">按原绑定重试</ElButton><ElButton @click="$emit('abandon')">明确放弃并重读</ElButton>
    </template>
    <ElButton v-if="pending.phase === 'committed'" @click="$emit('refresh')">读取已提交事实</ElButton>
  </section>
</template>
