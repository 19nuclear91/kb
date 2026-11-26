<script setup lang="ts">
import { computed } from 'vue';
import { ElButton } from 'element-plus';
import { useSession } from '../SessionProvider';
import type { Space } from '../types';
import { CardError } from './protocol';
import { useCardResource } from './useCardResource';
const props = defineProps<{ modelValue: string; managed?: boolean; required?: boolean; disabled?: boolean }>();
defineEmits<{ 'update:modelValue': [value: string] }>();
const auth = useSession();
const resource = useCardResource(() => props.managed ? 'managed-spaces' : 'visible-spaces', async signal => {
  const value = await auth.session.json<Space[]>('/api/admin/spaces', { signal });
  if (!Array.isArray(value) || value.some(item => typeof item.spaceId !== 'string' || typeof item.name !== 'string' || typeof item.archived !== 'boolean')) throw new CardError(503);
  return value.filter(space => props.managed ? space.manageable : space.viewable && !space.archived);
});
const spaces = computed(() => resource.data.value ?? []);
</script>
<template>
  <label>{{ managed ? '卡片管理空间' : '标准答案所属空间' }}
    <select :value="modelValue" :disabled="disabled || resource.loading.value || !!resource.error.value" @change="$emit('update:modelValue', ($event.target as HTMLSelectElement).value)">
      <option value="">{{ required ? '请选择空间' : '全部可见空间' }}</option>
      <option v-for="space in spaces" :key="space.spaceId" :value="space.spaceId">{{ space.name }}{{ space.archived ? '（已归档）' : '' }}</option>
    </select>
  </label>
  <span v-if="resource.error.value" role="alert">空间列表暂不可用。<ElButton @click="resource.reload">重新读取空间</ElButton></span>
</template>
