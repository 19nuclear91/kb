<script setup lang="ts">
import { computed, ref, shallowRef, watch } from 'vue';
import { ElButton } from 'element-plus';
import { useSession } from '../SessionProvider';
import type { QueryPage } from '../types';
import { useCursorList } from './hooks';
import { spacePaths, type Principal, type PrincipalType, type ResourceKind } from './spaceApi';
import ResourceStatus from './ResourceStatus.vue';

const props = defineProps<{ kind: ResourceKind; resourceId: string; initial?: Principal | null }>();
const emit = defineEmits<{ change: [value: Principal | null] }>();
const restored = shallowRef(props.initial ?? null);
const auth = useSession(), type = ref<PrincipalType>(restored.value?.principalType ?? 'USER'), q = ref(''), selected = ref(restored.value?.principalId ?? '');
const key = computed(() => `${props.kind}:${props.resourceId}:${type.value}:${q.value}`);
const list = useCursorList<Principal>((cursor, signal) => auth.session.json<QueryPage<Principal>>(spacePaths.principals(props.kind, props.resourceId, type.value, q.value, cursor), { signal }), key);
function clear() {
  restored.value = null;
  selected.value = '';
  emit('change', null);
}
watch(key, clear);
watch([() => list.loading, () => list.error], ([loading, error]) => {
  if ((loading || error) && !restored.value) {
    clear();
  }
}, { immediate: true });
// 恢复的是用户先前明确选择的正式草稿；候选分页不保证该主体仍在首页，提交继续由服务端校验。
const options = computed(() => restored.value && !list.items.some(item => item.principalId === restored.value!.principalId)
  ? [restored.value, ...list.items] : list.items);
function choose() {
  const next = options.value.find(item => item.principalId === selected.value) ?? null;
  restored.value = null;
  emit('change', next);
}
</script>

<template>
  <div class="panel"><label>主体类型<select v-model="type" aria-label="主体类型"><option value="USER">用户</option><option value="ROLE">角色</option><option value="DEPT">部门</option></select></label>
    <label>搜索主体<input v-model="q" aria-label="搜索主体" maxlength="256" /></label>
    <ResourceStatus :loading="list.loading" :error="list.error" :retry="list.reload" />
    <label>授权主体<select v-model="selected" aria-label="授权主体" :disabled="list.loading || !!list.error" @change="choose"><option value="">请选择主体</option><option v-for="item in options" :key="item.principalId" :value="item.principalId">{{ item.label }}</option></select></label>
    <ElButton v-if="list.hasMore" :disabled="list.loading" @click="list.more">继续读取候选</ElButton>
    <p v-if="type === 'DEPT'" class="muted">部门授权对子部门生效。</p>
  </div>
</template>
