<script setup lang="ts">
import { computed, ref, shallowRef, watch } from 'vue';
import { ElButton } from 'element-plus';
import { useSession } from '../../SessionProvider';
import { isAbort, messageOf } from '../../api';
import KbAlert from '../../ui/KbAlert.vue';
import { metadataApi, type Tag } from './api';
import { searchApi, searchOptionsMessage } from '../../search/api';

const props = withDefaults(defineProps<{ kind: 'edit' | 'filter' | 'search'; documentId?: string | null; value: Tag[]; disabled?: boolean }>(), { documentId: null, disabled: false });
const emit = defineEmits<{ change: [tags: Tag[]] }>();
const auth = useSession();
const draft = ref(''), search = ref(''), revision = ref(0);
type State = { key: string; items: Tag[]; cursor: string | null; loading: boolean; error?: string };
const state = shallowRef<State>({ key: '', items: [], cursor: null, loading: true });
let active: AbortController | null = null;
const key = computed(() => `${props.kind}:${props.documentId}:${search.value}:${revision.value}`);
const current = computed<State>(() => state.value.key === key.value ? state.value : { key: key.value, items: [], cursor: null, loading: true });
function load(cursor: string | null, items: Tag[]) {
  active?.abort();
  const request = new AbortController();
  active = request;
  const session = auth.session, epoch = session.getSnapshot().epoch, at = key.value, kind = props.kind;
  const valid = () => !request.signal.aborted && active === request && session.getSnapshot().epoch === epoch;
  state.value = { key: at, items, cursor, loading: true };
  const operation = kind === 'search' ? searchApi.tags(session, search.value, cursor, request.signal) : metadataApi.tags(session, kind, props.documentId, search.value, cursor, request.signal);
  void operation.then(page => {
    if (valid()) {
      state.value = { key: at, items: [...items, ...page.items], cursor: page.nextCursor, loading: false };
    }
  }, error => {
    if (valid() && !isAbort(error)) {
      state.value = { key: at, items: [], cursor: null, loading: false, error: kind === 'search' ? searchOptionsMessage(error) : messageOf(error) };
    }
  });
}
watch([key, () => auth.session], (_, __, onCleanup) => {
  load(null, []);
  onCleanup(() => active?.abort());
}, { immediate: true });
function queryTags() {
  search.value = draft.value;
  revision.value++;
}
function keydown(event: KeyboardEvent) {
  if (event.key === 'Enter') {
    event.preventDefault();
    event.stopPropagation();
    if (!event.isComposing && event.keyCode !== 229) {
      queryTags();
    }
  }
}
const candidates = computed(() => current.value.items.filter(tag => !props.value.some(selected => selected.id === tag.id)));
</script>

<template>
  <div><p>{{ kind !== 'edit' ? '多个标签全部匹配' : '最多选择 20 个已有标签；停用项可保留或移除' }}</p>
    <ul><li v-for="tag in value" :key="tag.id">{{ tag.name }}{{ !tag.enabled ? '（停用）' : '' }} <ElButton size="small" :disabled="disabled" @click="emit('change', value.filter(item => item.id !== tag.id))">移除 {{ tag.name }}</ElButton></li></ul>
    <label>查找标签<input v-model="draft" aria-label="查找标签" @keydown="keydown" /></label>
    <ElButton size="small" @click="queryTags">查询标签</ElButton>
    <ElButton size="small" @click="revision++">重新读取标签</ElButton>
    <p v-if="current.loading" role="status">正在读取标签…</p><KbAlert v-if="current.error" type="warning" :title="current.error" />
    <div><ElButton v-for="tag in candidates" :key="tag.id" size="small" :disabled="disabled || current.loading || !!current.error || value.length >= 20 || (kind === 'edit' && !tag.enabled)" @click="emit('change', [...value, tag])">{{ tag.name }}{{ !tag.enabled ? '（停用）' : '' }}</ElButton></div>
    <ElButton v-if="current.cursor" size="small" :disabled="current.loading" @click="load(current.cursor, current.items)">继续读取标签</ElButton>
    <p v-if="!current.loading && !current.error" class="muted">{{ !current.items.length ? '暂无可选标签' : !current.cursor ? '标签候选已全部读取' : '仍有更多标签候选' }}</p>
  </div>
</template>
