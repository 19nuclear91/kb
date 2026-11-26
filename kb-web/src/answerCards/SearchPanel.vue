<script setup lang="ts">
import { ref, shallowRef, watch } from 'vue';
import { ElButton, ElInput } from 'element-plus';
import { useSession } from '../SessionProvider';
import Feedback from '../ui/Feedback.vue';
import SafeText from '../ui/SafeText.vue';
import SpacePicker from './SpacePicker.vue';
import CardItems from './CardItems.vue';
import { cardApi, cardText } from './api';
import { CardError } from './protocol';
import { useCardResource } from './useCardResource';
const props = defineProps<{ originalQuery?: string }>();
const auth = useSession(), query = ref(''), space = ref(''), localError = ref('');
const submitted = shallowRef<{ query: string; spaceId: string | null; serial: number } | null>(null);
let serial = 0;
const result = useCardResource(() => submitted.value ? JSON.stringify(submitted.value) : null,
  signal => cardApi.search(auth.session, submitted.value!, signal));
function submit() {
  result.clear(); localError.value = '';
  try { submitted.value = { query: cardText(query.value, 1, 256), spaceId: space.value || null, serial: ++serial }; }
  catch { submitted.value = null; localError.value = new CardError(400).message; }
}
watch(() => auth.state.epoch, () => { query.value = ''; space.value = ''; submitted.value = null; localError.value = ''; });
</script>
<template>
  <section class="panel" aria-label="标准答案独立搜索">
    <h2>标准答案</h2><p>查找经审核的答案原文，仅使用本区域的问题和空间条件。文档分类、标签、文件类型、日期及术语建议不会应用到这里。</p>
    <ElButton v-if="originalQuery !== undefined" @click="query = props.originalQuery ?? ''">用原问题查标准答案</ElButton>
    <form @submit.prevent="submit"><label>标准答案问题<ElInput v-model="query" aria-label="标准答案问题" /></label>
      <SpacePicker v-model="space" /><ElButton native-type="submit" :loading="result.loading.value">查询标准答案</ElButton>
    </form>
    <p v-if="submitted">已提交问题：<SafeText :text="submitted.query" />；空间：{{ submitted.spaceId ? `已限定空间 ${submitted.spaceId}` : '全部可见空间' }}</p>
    <Feedback :loading="result.loading.value" :error="localError || result.error.value" />
    <CardItems v-if="result.data.value" :items="result.data.value.items" />
  </section>
</template>
