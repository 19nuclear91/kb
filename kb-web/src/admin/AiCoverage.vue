<script setup lang="ts">
import KbAlert from '../ui/KbAlert.vue';
import type { AiMetadata } from './aiUsageApi';
import AiTime from './AiTime.vue';

defineProps<{ meta: AiMetadata }>();
</script>

<template><section class="panel" aria-label="采集覆盖"><KbAlert v-if="!meta.collectionEnabled" type="info" :title="meta.coverageStartedAt === null ? '尚未开始采集 AI 用量，历史不能按零调用解释。' : '当前已停止新调用采集，保留记录和既有收尾仍可查询。'" /><KbAlert v-if="meta.historyMayBeIncomplete" type="warning" title="此时间范围的历史可能不完整：可能早于覆盖起点、包含停采间隙，或普通历史已按保留期清理；异常保留记录不代表完整历史。" />
  <p>本次窗口：<AiTime :value="meta.from" /> 至 <AiTime :value="meta.to" />（含起点，不含终点）</p><p>读取时刻 asOf：<AiTime :value="meta.asOf" /> · 按 UTC 日分组 · 当前保留 {{ meta.retentionDays }} 天</p><p>首次覆盖：<template v-if="meta.coverageStartedAt === null">尚无覆盖记录</template><AiTime v-else :value="meta.coverageStartedAt" /></p>
  <details><summary>查看采集区间（{{ meta.coverageIntervals.length }}）</summary><p v-for="(interval, index) in meta.coverageIntervals" :key="index"><AiTime :value="interval.from" /> 至 <AiTime :value="interval.to" /></p><p v-if="meta.coverageIntervalsTruncated">覆盖区间较多，当前提示未包含全部区间。</p></details>
</section></template>
