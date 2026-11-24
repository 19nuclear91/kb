<script setup lang="ts">
import { computed, ref, watch } from 'vue';
import { ElButton } from 'element-plus';
import SafeText from '../ui/SafeText.vue';
import { useSession } from '../SessionProvider';
import { termApi, type Target, type Term, type DirectoryFilters } from './api';
import { useTermDirectory } from './useDirectory';
const props = defineProps<{ value: Target | null }>();
const emit = defineEmits<{ change: [target: Target]; ready: [ready: boolean] }>();
const auth = useSession(), draft = ref(''), submitted = ref<DirectoryFilters>({ nameQuery: '', enabled: true, limit: 20 });
const list = useTermDirectory<Term>(async (cursor, signal) => {
  const page = await termApi.query(auth.session, 'TERM', submitted.value, cursor, signal); return { ...page, items: page.items as Term[] };
});
const ready = computed(() => !list.state.value.loading && !list.state.value.error && props.value?.enabled === true);
watch(ready, value => emit('ready', value), { immediate: true });
function query() { submitted.value = { nameQuery: draft.value, enabled: true, limit: 20 }; void list.reload(); }
</script>
<template>
  <section aria-label="选择目标术语">
    <label>目标名称查询<input v-model="draft" aria-label="目标名称查询" @keydown.enter.prevent="query" /></label><ElButton @click="query">查询目标</ElButton>
    <ElButton @click="list.reload">重新读取目标</ElButton><p v-if="list.state.value.loading" role="status">正在读取目标…</p>
    <p v-if="list.state.value.error" role="alert">{{ list.state.value.error }}</p>
    <p v-if="value">已选目标：<SafeText :text="value.canonicalName" /> · {{ value.termId }}{{ value.enabled ? '' : '（停用，不可保存内容）' }}</p>
    <ul><li v-for="term in list.state.value.items" :key="term.termId"><ElButton :disabled="!term.enabled || !!list.state.value.error || list.state.value.loading" @click="emit('change', term)"><SafeText :text="term.canonicalName" /></ElButton><SafeText :text="term.description" /></li></ul>
    <ElButton v-if="list.state.value.cursor" :disabled="list.state.value.loading" @click="list.more">继续读取目标</ElButton>
  </section>
</template>
