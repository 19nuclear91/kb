<script setup lang="ts">
import { computed, ref } from 'vue';
import { ElButton } from 'element-plus';
import { useDocumentOperations } from '../documents/OperationContext';
import KbAlert from '../ui/KbAlert.vue';
import { useThread } from './thread';
import DeleteDialog from './DeleteDialog.vue';

const props = defineProps<{ erased: ReadonlySet<string>; onRecover?: () => void }>();
const documents = useDocumentOperations(), thread = useThread(), target = ref<string | null>(null);
const known = computed(() => [...thread.own].filter(id => !props.erased.has(id)));
function done() {
  thread.erased(target.value!);
  target.value = null;
}
</script>

<template>
  <KbAlert type="warning" title="该版本的评论当前不可访问，已清理页面中的评论内容。"><template v-if="onRecover" #action><ElButton @click="onRecover">重新核对</ElButton></template></KbAlert>
  <template v-if="known.length"><p class="muted">仍可删除本页已知的本人评论；不会显示正文或重新读取讨论。</p>
    <ul class="admin-list"><li v-for="id in known" :key="id"><span>本人评论 {{ id.slice(0, 8) }}</span><ElButton size="small" type="danger" plain :disabled="documents.state.exclusive" @click="target = id">删除本人评论</ElButton></li></ul>
  </template>
  <DeleteDialog v-if="target" :id="target" @close="target = null" @done="done" />
</template>
