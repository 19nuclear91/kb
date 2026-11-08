<script setup lang="ts">
import { computed, shallowRef } from 'vue';
import { ElButton, ElTag } from 'element-plus';
import { useDocumentOperations } from '../documents/OperationContext';
import type { Binding } from '../admin/hooks';
import type { CommentItem } from './api';
import { useThread, when } from './thread';
import DeleteDialog from './DeleteDialog.vue';

const props = defineProps<{ item: CommentItem; erased: ReadonlySet<string> }>();
const emit = defineEmits<{ erased: [] }>();
const documents = useDocumentOperations(), thread = useThread();
const copies = shallowRef<readonly Binding[] | null>(null);
const gone = computed(() => props.item.deleted || props.erased.has(props.item.commentId));
function done() {
  const snapshot = copies.value ?? [];
  copies.value = null;
  thread.erased(props.item.commentId, snapshot);
  emit('erased');
}
</script>

<template>
  <p v-if="gone" class="muted">该评论已删除 · {{ when(item.createdAt) }}</p>
  <template v-else>
    <div class="comment-meta"><strong>{{ item.author!.label }}</strong><ElTag v-if="item.isOwn" type="info">本人</ElTag><span class="muted">{{ when(item.createdAt) }}</span></div>
    <pre class="content-text">{{ item.body }}</pre>
    <ElButton v-if="item.canDelete" size="small" type="danger" plain :disabled="documents.state.exclusive" @click="copies = thread.copies({ parentId: item.parentId, body: item.body! })">删除评论</ElButton>
    <DeleteDialog v-if="copies" :id="item.commentId" @close="copies = null" @done="done" />
  </template>
</template>
