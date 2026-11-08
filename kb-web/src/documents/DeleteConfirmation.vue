<script setup lang="ts">
import { ElButton, ElSpace } from 'element-plus';
import { canDelete } from './capabilities';
import type { DeletionState } from './deletion';
import type { OperationState } from './operations';
import KbAlert from '../ui/KbAlert.vue';
import KbDialog from '../ui/KbDialog.vue';

defineProps<{ state: DeletionState; operations: OperationState }>();
const emit = defineEmits<{ cancel: []; confirm: [acknowledge: boolean] }>();
</script>

<template>
  <KbDialog title="删除整份文档" :open="state.phase === 'checking' || state.phase === 'confirming'" @close="emit('cancel')">
    <p v-if="state.phase === 'checking'" role="status">正在重新读取文档与删除资格…</p>
    <p v-if="state.facts" aria-label="删除目标">{{ state.facts.detail.title }}</p>
    <KbAlert type="warning" title="将删除整份文档及全部版本，取消未完成的处理和审核，当前无法恢复。" description="操作范围不受当前所选版本影响；不承诺原件立即物理擦除。" />
    <p v-if="operations.pending > 0" role="status">仍有文档操作等待结果，结束前不能提交删除。</p>
    <KbAlert v-if="operations.uncertain > 0" type="warning" title="先前操作可能已经执行，结果仍未确认。" description="若仍要删除，请再次明确确认整份文档及全部版本的删除。" />
    <p v-if="state.uncertainResult">先前删除结果仍未确认；当前可访问不表示原请求失败或回滚。</p>
    <KbAlert v-if="state.message" type="warning" :title="state.message" />
    <ElSpace style="margin-top: 16px"><ElButton @click="emit('cancel')">取消</ElButton>
      <ElButton type="danger" :disabled="state.phase !== 'confirming' || !canDelete(state.facts?.capabilities) || operations.pending > 0 || !operations.exclusive" @click="emit('confirm', state.acknowledgement !== undefined)">{{ state.acknowledgement !== undefined ? '仍要删除整份文档' : '确认删除整份文档' }}</ElButton>
    </ElSpace>
  </KbDialog>
</template>
