<script setup lang="ts">
import { computed } from 'vue';
import { ElSpace, ElTag } from 'element-plus';
import { usageLabel, visibleTurn } from '../logic';
import type { Citation, Turn } from '../types';
import KbAlert from './KbAlert.vue';
import References from './References.vue';
import SafeText from './SafeText.vue';

const props = defineProps<{ value: Turn }>();
const emit = defineEmits<{ open: [value: Citation] }>();
const statusNames: Record<string, string> = { RUNNING: '处理中', COMPLETED: '已完成', FAILED: '失败 · 未完成', CANCELLED: '已停止 · 未完成', INTERRUPTED: '已中断 · 未完成' };
const turn = computed(() => visibleTurn(props.value));
const settling = computed(() => turn.value.status === 'COMPLETED' && turn.value.settlementState !== 'CONFIRMED' && turn.value.settlementState !== 'SETTLED');
</script>

<template>
  <article class="turn">
    <div class="question"><div class="muted">你</div><SafeText :text="turn.question" /></div>
    <div class="answer">
      <ElSpace style="margin-bottom: 12px">
        <ElTag :type="turn.status === 'COMPLETED' ? 'success' : 'info'">{{ statusNames[turn.status] ?? '状态待确认' }}</ElTag>
        <ElTag v-if="settling" type="info">用量确认中</ElTag>
      </ElSpace>
      <KbAlert v-if="turn.visibility === 'BLOCKED'" type="info" title="当前无法查看这条回答。" />
      <template v-else>
        <SafeText :text="turn.answer ?? ''" />
        <References :citations="turn.citations ?? []" @open="emit('open', $event)" />
      </template>
      <div class="muted" style="margin-top: 14px">{{ usageLabel(turn) }}</div>
    </div>
  </article>
</template>
