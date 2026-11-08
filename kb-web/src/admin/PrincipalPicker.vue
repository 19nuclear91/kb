<script setup lang="ts">
import { computed, ref, watch } from 'vue';
import { ElButton } from 'element-plus';
import { useSession } from '../SessionProvider';
import type { QueryPage } from '../types';
import { useCursorList } from './hooks';
import { spacePaths, type Principal, type PrincipalType, type ResourceKind } from './spaceApi';
import ResourceStatus from './ResourceStatus.vue';

const props = defineProps<{ kind: ResourceKind; resourceId: string }>();
const emit = defineEmits<{ change: [value: Principal | null] }>();
const auth = useSession(), type = ref<PrincipalType>('USER'), q = ref(''), selected = ref('');
const key = computed(() => `${props.kind}:${props.resourceId}:${type.value}:${q.value}`);
const list = useCursorList<Principal>((cursor, signal) => auth.session.json<QueryPage<Principal>>(spacePaths.principals(props.kind, props.resourceId, type.value, q.value, cursor), { signal }), key);
function clear() {
  selected.value = '';
  emit('change', null);
}
watch(key, clear, { immediate: true });
watch([() => list.loading, () => list.error], ([loading, error]) => {
  if (loading || error) {
    clear();
  }
}, { immediate: true });
function choose() {
  emit('change', list.items.find(item => item.principalId === selected.value) ?? null);
}
</script>

<template>
  <div class="panel"><label>主体类型<select v-model="type" aria-label="主体类型"><option value="USER">用户</option><option value="ROLE">角色</option><option value="DEPT">部门</option></select></label>
    <label>搜索主体<input v-model="q" aria-label="搜索主体" maxlength="256" /></label>
    <ResourceStatus :loading="list.loading" :error="list.error" :retry="list.reload" />
    <label>授权主体<select v-model="selected" aria-label="授权主体" :disabled="list.loading || !!list.error" @change="choose"><option value="">请选择主体</option><option v-for="item in list.items" :key="item.principalId" :value="item.principalId">{{ item.label }}</option></select></label>
    <ElButton v-if="list.hasMore" :disabled="list.loading" @click="list.more">继续读取候选</ElButton>
    <p v-if="type === 'DEPT'" class="muted">部门授权对子部门生效。</p>
  </div>
</template>
