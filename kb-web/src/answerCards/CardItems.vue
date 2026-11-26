<script setup lang="ts">
import { RouterLink } from 'vue-router';
import SafeText from '../ui/SafeText.vue';
import CardDates from './CardDates.vue';
import { stewardLabel } from './labels';
import type { Item } from './types';
defineProps<{ items: Item[] }>();
</script>
<template>
  <p v-if="!items.length">当前没有匹配的标准答案。</p>
  <article v-for="item in items" :key="item.cardId" class="panel">
    <h3><RouterLink :to="item.detailPath"><SafeText :text="item.question" /></RouterLink></h3>
    <p>经审核 · 修订 {{ item.revisionNo }} · {{ stewardLabel[item.stewardshipState] }}</p>
    <SafeText :text="item.answerExcerpt" /><p v-if="item.truncated">以上为原文摘要，完整答案请打开固定修订。</p>
    <p>适用范围：</p><SafeText :text="item.applicabilityText" /><p><CardDates :value="item.dates" /></p>
  </article>
</template>
