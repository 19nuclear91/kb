<script setup lang="ts">
import { computed, ref, useId, watch } from 'vue';
import { ElFormItem, ElInput, type FormItemInstance, type FormItemRule } from 'element-plus';

const props = defineProps<{ name: string; label: string; values: Record<string, unknown> }>();
const emit = defineEmits<{ change: [name: string, value: string] }>();
const id = useId(), confirm = ref<FormItemInstance>();
const rules = computed<FormItemRule[]>(() => [{ required: true, message: `请再次输入${props.label}` }, {
  validator: (_, value, callback) => callback(value === props.values[props.name] ? undefined : new Error('两次密码不一致')),
}]);
watch(() => props.values[props.name], () => {
  if (props.values[`${props.name}Confirm`]) {
    void confirm.value?.validate('').catch(() => {});
  }
});
</script>

<template>
  <ElFormItem :prop="name" :label="label" :for="`${id}-password`" :rules="[{ required: true, message: `请输入${label}` }]">
    <ElInput :id="`${id}-password`" :model-value="String(values[name] ?? '')" :aria-label="label" type="password" show-password autocomplete="new-password" @update:model-value="emit('change', name, $event)" />
  </ElFormItem>
  <ElFormItem ref="confirm" :prop="`${name}Confirm`" :label="`确认${label}`" :for="`${id}-confirm`" :rules="rules">
    <ElInput :id="`${id}-confirm`" :model-value="String(values[`${name}Confirm`] ?? '')" :aria-label="`确认${label}`" type="password" show-password autocomplete="new-password" @update:model-value="emit('change', `${name}Confirm`, $event)" />
  </ElFormItem>
</template>
