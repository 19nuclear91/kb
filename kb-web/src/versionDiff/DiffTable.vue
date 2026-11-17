<script setup lang="ts">
import type { Table } from './api';
defineProps<{ value: Table }>();
const labels = { EQUAL: '未变化', ADDED: '新增行', REMOVED: '删除行', MODIFIED: '修改行' };
</script>
<template>
  <section v-if="value.status === 'RESTRUCTURED'"><p>表格结构已调整，无法逐格对应（RESTRUCTURED）</p>
    <div class="sides"><div v-for="(rows, side) in [value.base, value.target]" :key="side"><h4>{{ side === 0 ? '基准表格' : '目标表格' }}</h4>
      <table><tbody><tr v-for="(row, index) in rows" :key="index"><td v-for="(cell, column) in row" :key="column">{{ cell }}</td></tr></tbody></table>
    </div></div>
  </section>
  <table v-else><tbody><tr v-for="(row, index) in value.rows" :key="index" :data-operation="row.op"><th>{{ labels[row.op] }}</th>
    <td v-for="(cell, column) in row.after ?? row.before ?? []" :key="column">
      <template v-if="row.cells.some(item => item.column === column)"><del>{{ row.cells.find(item => item.column === column)?.before }}</del> → <ins>{{ cell }}</ins></template>
      <template v-else>{{ cell }}</template>
    </td></tr></tbody></table>
</template>
<style scoped>.sides { display: grid; grid-template-columns: repeat(2, minmax(0, 1fr)); gap: 1rem; } td, th { border: 1px solid #b8c4ca; padding: .5rem; white-space: pre-wrap; overflow-wrap: anywhere; } table { border-collapse: collapse; }</style>
