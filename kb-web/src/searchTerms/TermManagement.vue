<script setup lang="ts">
import { ref } from 'vue';
import { ElButton } from 'element-plus';
import PageHeader from '../ui/PageHeader.vue';
import TermDirectory from './TermDirectory.vue';
import type { TermKind } from './api';
const kind = ref<TermKind>('TERM');
</script>
<template>
  <PageHeader title="企业术语词典" description="人工维护公司公开的术语组与单向错词；不改变文档授权" />
  <ElButton @click="kind = 'TERM'">术语组</ElButton><ElButton @click="kind = 'CORRECTION'">错词映射</ElButton>
  <TermDirectory :key="kind" :kind="kind" />
</template>
