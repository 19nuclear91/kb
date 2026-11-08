<script setup lang="ts">
import { computed, ref } from 'vue';
import { ElOption, ElSelect } from 'element-plus';
import OperationDialog from './OperationDialog.vue';
import { operationWrites, type EmbeddingProfile, type EmbeddingRun, type Exact } from './operationsApi';

const props = defineProps<{ value: { kind: 'start'; epoch: Exact } | { kind: 'resume'; run: EmbeddingRun }; catalog: EmbeddingProfile[]; allowed: boolean }>();
const emit = defineEmits<{ done: [value: unknown]; close: []; refresh: [] }>();
const target = ref(props.value.kind === 'resume' ? props.value.run.targetProfileId : props.catalog.find(row => row.ready)?.profile.profileId ?? '');
const ready = computed(() => props.catalog.some(row => row.ready && row.profile.profileId === target.value));
</script>

<template><OperationDialog :title="value.kind === 'start' ? '确认发起重建' : '确认恢复作业'" :scope="value.kind === 'start' ? 'embedding-start' : `embedding-resume:${value.run.runId}`" :max="500" :allowed="allowed && ready" :build="reason => value.kind === 'start' ? operationWrites.start(target, value.epoch, reason) : operationWrites.resume(value.run.runId, value.run.version, reason)" @done="emit('done', $event)" @close="emit('close')" @refresh="emit('refresh')">
  <template #description>{{ value.kind === 'start' ? '此操作影响全局检索：搜索保留授权 BM25，新解析任务等待。受理后不能取消或即时切回旧向量。' : `继续原作业 ${value.run.runId}，保留成功进度并沿原目标执行。` }}</template>
  <template #default="{ locked }"><div v-if="value.kind === 'start'" style="margin-bottom: 16px"><label for="embedding-target">目标模型配置</label><ElSelect id="embedding-target" v-model="target" aria-label="目标模型配置" :disabled="locked" style="display: block"><ElOption v-for="row in catalog" :key="row.profile.profileId" :value="row.profile.profileId" :label="`${row.profile.profileId} · ${row.ready ? '就绪' : '未就绪'}`" :disabled="!row.ready" /></ElSelect></div><p v-else>目标模型配置：{{ target }}</p></template>
</OperationDialog></template>
