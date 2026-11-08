<script setup lang="ts">
import { computed, onScopeDispose } from 'vue';
import { ElButton } from 'element-plus';
import type { ApiError } from '../api';
import KbAlert from '../ui/KbAlert.vue';
import { useExternalStore } from '../useExternalStore';
import { provideDocumentOperations } from '../documents/OperationContext';
import type { DocumentOperations } from '../documents/operations';
import { spaceWrites, type DeleteDocumentResult, type ManagedDocument } from './spaceApi';
import ResourceWriteDialog from './ResourceWriteDialog.vue';

const props = defineProps<{ id: string; owner: ManagedDocument | undefined; title: string | undefined; operations: DocumentOperations; open: boolean; onDone: (result: DeleteDocumentResult) => void; onRejected: (error: ApiError) => void; refresh: () => void }>();
const emit = defineEmits<{ open: []; close: [] }>();
const state = useExternalStore(() => props.operations), blocked = computed(() => state.value.pending > 0);
// 删除本身不登记为普通写入，否则互斥会拦下自己。
provideDocumentOperations(() => null);
onScopeDispose(() => props.operations.cancelDelete());
</script>

<template><section class="panel"><h2>删除文档</h2><p>按所属空间的管理资格删除整份文档，无需 edit；{{ owner?.spaceArchived ? '所属空间已归档也可删除，无需先取消归档。' : '删除后不可恢复。' }}</p>
  <ElButton type="danger" plain :disabled="!owner || open" @click="emit('open')">删除文档</ElButton>
  <ResourceWriteDialog v-if="open" title="删除整份文档" :scope="`document:${id}:delete`" :on-refresh="refresh" :disabled="blocked" :retry-disabled="blocked" :build="() => spaceWrites.deleteDocument(id)" :on-done="onDone" :on-rejected="onRejected" @close="emit('close')">
    <p aria-label="删除目标">删除目标：{{ title ?? `文档 ${id}` }}</p><KbAlert type="warning" title="将删除整份文档及全部版本，取消未完成的解析处理与审核，该文档的例外授权随之失效，不可恢复。" :description="`资格按所属空间的管理资格判定；${owner?.spaceArchived ? '所属空间已归档无需先取消归档。' : ''}不承诺原件立即物理擦除。`" />
    <p v-if="blocked" role="status">仍有其他管理写入等待结果，结束前不能提交删除。</p><KbAlert v-if="state.uncertain > 0" type="warning" title="先前操作可能已经执行，结果仍未确认。" />
  </ResourceWriteDialog>
</section></template>
