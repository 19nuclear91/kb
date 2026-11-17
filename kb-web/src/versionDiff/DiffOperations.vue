<script setup lang="ts">
import { computed } from 'vue';
import type { Operation } from './api';
import DiffTable from './DiffTable.vue';
const props = defineProps<{ operations: Operation[] }>();
const groups = computed(() => {
  const groups: { equal: boolean; items: { index: number; operation: Operation }[] }[] = [];
  props.operations.forEach((operation, index) => {
    const equal = operation.op === 'EQUAL';
    if (equal && groups.at(-1)?.equal) groups.at(-1)!.items.push({ index, operation });
    else groups.push({ equal, items: [{ index, operation }] });
  });
  return groups;
});
const labels = { ADDED: '新增', REMOVED: '删除', MODIFIED: '修改', MOVED: '移动', EQUAL: '未变化' };
</script>
<template>
  <section aria-label="结构差异"><template v-for="group in groups" :key="group.items[0]!.index">
    <details v-if="group.equal"><summary>未变化 {{ group.items.length }} 块，展开查看</summary>
      <pre v-for="item in group.items" :id="`diff-op-${item.index}`" :key="item.index">{{ item.operation.target?.text }}</pre>
    </details>
    <article v-else v-for="item in group.items" :id="`diff-op-${item.index}`" :key="item.index" class="panel">
      <h3>{{ labels[item.operation.op] }} · {{ item.operation.headingPath ?? item.operation.kind }}</h3>
      <p>基准块 {{ item.operation.base?.index ?? '无' }} → 目标块 {{ item.operation.target?.index ?? '无' }}
        <span v-if="item.operation.base?.pageNo || item.operation.target?.pageNo"> · 页码 {{ item.operation.base?.pageNo ?? '未知' }} → {{ item.operation.target?.pageNo ?? '未知' }}</span></p>
      <p v-if="item.operation.op === 'MOVED'">原文移动：来源 {{ item.operation.base?.index }}，去向 {{ item.operation.target?.index }}</p>
      <DiffTable v-if="item.operation.table" :value="item.operation.table" />
      <p v-else-if="item.operation.op === 'MODIFIED' && !item.operation.segmentsOmitted" class="fragments">
        <template v-for="(segment, index) in item.operation.segments" :key="index"><del v-if="segment.type === 'DELETE'">{{ segment.text }}</del><ins v-else-if="segment.type === 'INSERT'">{{ segment.text }}</ins><span v-else>{{ segment.text }}</span></template>
      </p>
      <template v-else-if="item.operation.op === 'MODIFIED'"><p>该块未逐字比较，以下为两侧全文</p><div class="sides"><pre>{{ item.operation.base?.text }}</pre><pre>{{ item.operation.target?.text }}</pre></div></template>
      <pre v-else>{{ (item.operation.target ?? item.operation.base)?.text }}</pre>
    </article>
  </template></section>
</template>
<style scoped>pre, .fragments { white-space: pre-wrap; overflow-wrap: anywhere; } .sides { display: grid; grid-template-columns: repeat(2, minmax(0, 1fr)); gap: 1rem; } del { background: #ffe1df; } ins { background: #d8f3e4; }</style>
