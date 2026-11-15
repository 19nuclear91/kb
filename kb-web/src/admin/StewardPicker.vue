<script setup lang="ts">
import { ref, watch } from 'vue';
import { ElButton } from 'element-plus';
import { useSession } from '../SessionProvider';
import { useCursorList } from './hooks';
import Feedback from '../ui/Feedback.vue';
import { validityApi, type Candidate } from '../validity/api';

const props = defineProps<{ documentId: string; disabled?: boolean }>();
const emit = defineEmits<{ select: [value: Candidate | null]; denied: [status: number] }>();
const auth = useSession(), name = ref(''), submitted = ref(''), selected = ref('');
const list = useCursorList<Candidate>((cursor, signal) => validityApi.candidates(auth.session, props.documentId, submitted.value, cursor, signal), () => `${props.documentId}:${submitted.value}`);
watch(name, () => { selected.value = ''; emit('select', null); });
watch(() => list.status, status => { if (status === 403 || status === 404) { selected.value = ''; emit('select', null); emit('denied', status); } });
function choose(event: Event) { selected.value = (event.target as HTMLSelectElement).value; emit('select', list.items.find(item => item.userId === selected.value) ?? null); }
</script>
<template><div><label>责任人名称<input v-model="name" aria-label="责任人名称" :disabled="disabled" @keydown.enter.prevent="submitted = name.trim()" /></label><ElButton :disabled="disabled" @click="submitted = name.trim(); list.reload()">检索责任人</ElButton>
  <Feedback :loading="list.loading" :error="list.error" :retry="list.reload" /><label>合格责任人<select :value="selected" aria-label="合格责任人" :disabled="disabled || list.loading" @change="choose"><option value="">请选择</option><option v-for="item in list.items" :key="item.userId" :value="item.userId">{{ item.displayName }}</option></select></label>
  <ElButton v-if="list.hasMore" :disabled="disabled || list.loading" @click="list.more">更多责任人候选</ElButton>
</div></template>
