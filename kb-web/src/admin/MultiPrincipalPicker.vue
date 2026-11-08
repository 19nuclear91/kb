<script setup lang="ts">
import { computed, ref } from 'vue';
import { ElButton } from 'element-plus';
import { useSession } from '../SessionProvider';
import type { QueryPage } from '../types';
import { useCursorList } from './hooks';
import { spacePaths, type Principal, type PrincipalType } from './spaceApi';
import ResourceStatus from './ResourceStatus.vue';

const props = defineProps<{ resourceId: string; selected: Principal[] }>();
const emit = defineEmits<{ change: [value: Principal[]] }>();
const principalKey = (principal: Principal) => `${principal.principalType}:${principal.principalId}`;
const auth = useSession(), type = ref<PrincipalType>('USER'), q = ref('');
const list = useCursorList<Principal>((cursor, signal) => auth.session.json<QueryPage<Principal>>(
  spacePaths.principals('spaces', props.resourceId, type.value, q.value, cursor), { signal }), () => `spaces:${props.resourceId}:${type.value}:${q.value}`);
const keys = computed(() => new Set(props.selected.map(principalKey)));
function add(principal: Principal) {
  if (!list.loading && !list.error && !keys.value.has(principalKey(principal))) {
    emit('change', [...props.selected, principal]);
  }
}
</script>

<template>
  <div class="panel"><label>主体类型<select v-model="type" aria-label="主体类型"><option value="USER">用户</option><option value="ROLE">角色</option><option value="DEPT">部门</option></select></label>
    <label>搜索主体<input v-model="q" aria-label="搜索主体" maxlength="256" /></label>
    <ResourceStatus :loading="list.loading" :error="list.error" :retry="list.reload" />
    <ul aria-label="候选主体"><li v-for="principal in list.items" :key="principalKey(principal)"><span>{{ principal.label }}</span>{{ ' ' }}
      <ElButton :aria-label="`添加 ${principal.label}`" :disabled="list.loading || !!list.error || keys.has(principalKey(principal))" @click="add(principal)">{{ keys.has(principalKey(principal)) ? '已选择' : '添加' }}</ElButton></li></ul>
    <ElButton v-if="list.hasMore" :disabled="list.loading || !!list.error" @click="list.more">继续读取候选</ElButton>
    <p>已选主体：{{ selected.length }}；上限为 50，可逐个移除。</p>
    <ul aria-label="已选主体"><li v-for="principal in selected" :key="principalKey(principal)"><span>{{ principal.principalType }} · {{ principal.label }}</span>{{ ' ' }}
      <ElButton :aria-label="`移除 ${principal.principalType} ${principal.label}`" @click="emit('change', selected.filter(item => principalKey(item) !== principalKey(principal)))">移除</ElButton></li></ul>
    <p class="muted">部门授权对子部门生效。</p>
  </div>
</template>
