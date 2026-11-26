<script setup lang="ts">
import { reactive, ref, shallowRef, watch } from 'vue';
import { ElButton, ElInput } from 'element-plus';
import { useSession } from '../SessionProvider';
import SafeText from '../ui/SafeText.vue';
import Feedback from '../ui/Feedback.vue';
import { cardApi, content } from './api';
import { CardError } from './protocol';
import CardDates from './CardDates.vue';
import { useCardResource } from './useCardResource';
import type { Content, Revision, SourceKey, UntilKind } from './types';
const props = defineProps<{ initial: Revision | null; spaceId: string; locked: boolean }>();
const emit = defineEmits<{ save: [value: Content]; denied: [] }>();
const auth = useSession(), error = ref(''), name = ref('');
const values = reactive({ question: '', aliases: '', answer: '', applicabilityText: '', effectiveFrom: '', untilKind: 'UNDECLARED' as UntilKind, effectiveUntil: '' });
const chosen = shallowRef<Array<SourceKey & { title: string }>>([]);
const submitted = shallowRef<{ name: string; cursor: string | null; serial: number } | null>(null);
let serial = 0;
const candidates = useCardResource(() => !props.locked && submitted.value ? `${props.spaceId}:${JSON.stringify(submitted.value)}` : null,
  signal => cardApi.sources(auth.session, props.spaceId, submitted.value!.name, submitted.value!.cursor, signal), () => { chosen.value = []; emit('denied'); });
watch([() => props.initial, () => props.spaceId, () => auth.state.epoch], () => {
  const value = props.initial;
  Object.assign(values, { question: value?.question ?? '', aliases: value?.aliases.join('\n') ?? '', answer: value?.answer ?? '', applicabilityText: value?.applicabilityText ?? '',
    effectiveFrom: value?.dates.effectiveFrom ?? '', untilKind: value?.dates.untilKind ?? 'UNDECLARED', effectiveUntil: value?.dates.effectiveUntil ?? '' });
  chosen.value = value?.sources.map(source => ({ documentId: source.documentId, versionId: source.versionId, applicabilityVersion: source.applicabilityVersion, title: source.title })) ?? [];
  name.value = ''; submitted.value = null; error.value = '';
}, { immediate: true });
function choose(source: SourceKey & { title: string }) {
  chosen.value = [...chosen.value.filter(value => value.documentId !== source.documentId), { documentId: source.documentId, versionId: source.versionId, applicabilityVersion: source.applicabilityVersion, title: source.title }];
}
function save() {
  if (props.locked) return;
  try {
    error.value = '';
    emit('save', content({ question: values.question, aliases: values.aliases ? values.aliases.split('\n') : [], answer: values.answer, applicabilityText: values.applicabilityText,
      dates: { effectiveFrom: values.effectiveFrom || null, untilKind: values.untilKind, effectiveUntil: values.untilKind === 'DATE' ? values.effectiveUntil || null : null },
      sources: chosen.value.map(({ documentId, versionId, applicabilityVersion }) => ({ documentId, versionId, applicabilityVersion })) }));
  } catch { error.value = new CardError(400).message; }
}
</script>
<template>
  <section class="panel" aria-label="编辑标准答案草稿"><h2>编辑草稿</h2>
    <form @submit.prevent="save"><fieldset :disabled="locked"><legend>纯文本内容与固定依据</legend>
      <label>标准问题<ElInput v-model="values.question" aria-label="标准问题" /></label>
      <label>常见问法（每行一条）<ElInput v-model="values.aliases" type="textarea" aria-label="常见问法" /></label>
      <label>答案原文<ElInput v-model="values.answer" type="textarea" :rows="8" aria-label="答案原文" /></label>
      <label>适用范围<ElInput v-model="values.applicabilityText" type="textarea" aria-label="适用范围" /></label>
      <label>生效日期<input v-model="values.effectiveFrom" type="date" min="1900-01-01" max="2999-12-31" /></label>
      <label>截止类型<select v-model="values.untilKind"><option value="UNDECLARED">未声明</option><option value="OPEN_ENDED">长期有效</option><option value="DATE">指定日期</option></select></label>
      <label v-if="values.untilKind === 'DATE'">截止日期<input v-model="values.effectiveUntil" type="date" min="1900-01-01" max="2999-12-31" /></label>
      <h3>已明确选择的依据</h3><p>来源变化时不会自动替换。请核对候选后点击“明确选用此版本与日期”。</p>
      <p v-for="source in chosen" :key="source.documentId"><SafeText :text="source.title" /> · 版本ID {{ source.versionId }} · 日期修订 {{ source.applicabilityVersion }}
        <ElButton @click="chosen = chosen.filter(value => value.documentId !== source.documentId)">移除依据</ElButton></p>
      <label>依据名称<ElInput v-model="name" aria-label="依据名称" /></label><ElButton @click="submitted = { name, cursor: null, serial: ++serial }">查询当前依据</ElButton>
      <Feedback :loading="candidates.loading.value" :error="candidates.error.value" />
      <div v-for="source in candidates.data.value?.items" :key="source.documentId" class="panel"><SafeText :text="source.title" /> · 文件版本 {{ source.versionNo }}<p><CardDates :value="source.dates" /></p>
        <ElButton @click="choose(source)">明确选用此版本与日期</ElButton></div>
      <ElButton v-if="candidates.data.value?.hasMore" @click="submitted = { name: submitted!.name, cursor: candidates.data.value.nextCursor, serial: ++serial }">下一页依据</ElButton>
      <p v-if="error" role="alert">{{ error }}</p><ElButton native-type="submit" type="primary">保存草稿</ElButton>
    </fieldset></form>
  </section>
</template>
