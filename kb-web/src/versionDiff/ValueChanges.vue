<script setup lang="ts">
import type { ValueChange } from './api';
defineProps<{ values: ValueChange[] }>();
const labels = { DATE: '日期', MONEY: '金额', PERCENT: '百分比', CLAUSE: '条款编号' };
function locate(index: number) { document.getElementById(`diff-op-${index}`)?.scrollIntoView?.({ block: 'start', behavior: 'smooth' }); }
</script>
<template>
  <aside aria-label="关键数值变化"><h2>关键数值变化</h2><p>只包含修改内容中按规则识别到的数值，不代表全部变化。</p>
    <p v-if="!values.length">修改内容中未识别到日期、金额、百分比或条款编号变化</p>
    <template v-for="(label, type) in labels" :key="type"><section v-if="values.some(value => value.type === type)"><h3>{{ label }}</h3>
      <button v-for="(value, index) in values.filter(value => value.type === type)" :key="index" @click="locate(value.operationIndex)">
        {{ value.before ?? '仅后值' }} → {{ value.after ?? '仅前值' }} {{ value.headingPath ?? '' }}
      </button></section></template>
  </aside>
</template>
