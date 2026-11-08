<script setup lang="ts">
import { computed } from 'vue';
import { ElButton, ElSpace } from 'element-plus';
import { errorMessage } from '../api';
import { canDelete } from './capabilities';
import type { DeletionState } from './deletion';
import KbAlert from '../ui/KbAlert.vue';

const props = defineProps<{ state: DeletionState }>();
const emit = defineEmits<{ verify: []; reconfirm: [] }>();
const allowed = computed(() => canDelete(props.state.facts?.capabilities));
const title = computed(() => {
  const state = props.state;
  return state.phase === 'unavailable' ? '文档当前不可访问。'
    : state.phase === 'rejected' ? state.rejection ? errorMessage(state.rejection) : state.message ?? '删除请求已被拒绝，请核对当前状态。'
      : state.facts ? allowed.value ? '当前仍可访问，原删除结果尚未确认。' : '当前可查看，但已无删除资格；原删除结果仍未确认。'
        : '删除结果尚未确认。';
});
const description = computed(() => props.state.phase === 'rejected' ? props.state.uncertainResult ? '本次请求已明确拒绝；先前删除结果仍未确认。核对不会重发删除。'
  : '本次删除已被拒绝；核对只说明当前资源事实，不会重发删除。'
  : props.state.attempted ? '当前不可访问不能证明删除成功；当前可访问也不能证明原请求失败。核对不会重发删除。' : undefined);
</script>

<template>
  <KbAlert v-if="state.phase === 'submitting'" type="info" title="删除请求已提交，正在等待结果。" description="离开页面不会撤销服务器已执行的操作。" />
  <KbAlert v-else-if="state.phase === 'verifying'" type="info" title="正在只读核对当前文档和操作资格…" />
  <KbAlert v-else-if="state.phase === 'confirmed'" type="success" title="文档已删除" />
  <section v-else-if="['uncertain', 'rejected', 'unavailable'].includes(state.phase)" class="panel" aria-label="删除结果">
    <KbAlert :type="state.phase === 'rejected' ? 'error' : 'warning'" :title="title" :description="description" />
    <p v-if="state.phase === 'rejected' && state.message && state.message !== title" role="status">{{ state.message }}</p>
    <ElSpace style="margin-top: 16px"><ElButton @click="emit('verify')">核对当前状态</ElButton><ElButton v-if="allowed" type="danger" plain @click="emit('reconfirm')">重新确认删除</ElButton></ElSpace>
  </section>
</template>
