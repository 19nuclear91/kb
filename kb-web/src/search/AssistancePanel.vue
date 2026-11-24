<script setup lang="ts">
import { ElButton } from 'element-plus';
import SafeText from '../ui/SafeText.vue';
import type { Assistance, Suggestion } from './assistedApi';
defineProps<{ value: Assistance; busy: boolean }>();
const emit = defineEmits<{ adopt: [suggestion: Suggestion, termId: string] }>();
</script>

<template>
  <section class="panel" aria-label="搜索辅助">
    <h2>{{ value.mode === 'ORIGINAL' ? '原词搜索' : '术语辅助' }}</h2>
    <p>本次有效查询：<SafeText :text="value.effectiveQuery" /></p>
    <div v-for="term in value.appliedTerms" :key="`${term.start}/${term.end}`">
      <span>已采用：</span><SafeText :text="term.matchedText" /> → <SafeText :text="term.canonicalName" />
      <span v-if="term.expressions.length"> · 补充表达：</span><SafeText v-for="expression in term.expressions" :key="expression" :text="expression + ' '" />
    </div>
    <div v-for="suggestion in value.suggestions" :key="`${suggestion.start}/${suggestion.end}`">
      <p>{{ suggestion.kind === 'CORRECTION' ? '是否搜索' : '请选择含义' }}：<SafeText :text="Array.from(value.originalQuery).slice(suggestion.start, suggestion.end).join('')" /></p>
      <div v-for="option in suggestion.options" :key="option.termId">
        <ElButton :disabled="busy" @click="emit('adopt', suggestion, option.termId)"><SafeText :text="option.canonicalName" /></ElButton>
        <SafeText :text="option.description" />
      </div>
    </div>
    <p class="muted">词条为公司公开提示，不证明存在可读资料，也不保证采用后有结果。</p>
  </section>
</template>
