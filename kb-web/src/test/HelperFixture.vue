<script setup lang="ts">
import { ref } from 'vue';
import { ElButton, ElForm, ElFormItem, ElOption, ElSelect } from 'element-plus';
import KbDialog from '../ui/KbDialog.vue';

defineProps<{ title: string }>();
const open = ref(true), space = ref<string>();
</script>

<template>
  <p>标题：{{ title }}</p>
  <ElButton @click="open = true">打开</ElButton>
  <KbDialog :open="open" title="确认操作" @close="open = false">
    <ElForm label-position="top">
      <ElFormItem label="空间">
        <ElSelect v-model="space" placeholder="选择空间">
          <ElOption label="甲空间" value="1" />
          <ElOption label="乙空间" value="2" />
        </ElSelect>
      </ElFormItem>
    </ElForm>
    <p>已选：{{ space ?? '' }}</p>
  </KbDialog>
</template>
