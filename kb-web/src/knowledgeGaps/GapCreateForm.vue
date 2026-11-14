<script setup lang="ts">
import { computed, onScopeDispose, ref } from 'vue';
import { ElButton, ElCheckbox, ElInput, ElOption, ElSelect } from 'element-plus';
import { useSession } from '../SessionProvider';
import { useExternalStore } from '../useExternalStore';
import KbAlert from '../ui/KbAlert.vue';
import { GapCommands, gapIntent, gapIntentKey, type GapSubmission } from './commands';
import { gapTextLength } from './input';
import { gapCategories, gapCategoryLabels, gapShareNotice, type GapCategory, type GapReceipt, type GapSource } from './types';

const props = withDefaults(defineProps<{ source?: GapSource; initialDescription?: string }>(), { source: 'MANUAL', initialDescription: '' });
const emit = defineEmits<{ submitted: [receipt: GapReceipt]; cancel: [] }>();
const auth = useSession(), commands = new GapCommands(auth.session, 'create');
const state = useExternalStore(() => commands);
const description = ref(props.initialDescription), category = ref<GapCategory>('OTHER'), acknowledged = ref(false), submittedKey = ref('');
const count = computed(() => { try { return gapTextLength(description.value); } catch { return -1; } });
const valid = computed(() => count.value >= 1 && count.value <= 4000 && acknowledged.value);
const build = (): GapSubmission => ({ action: 'create', category: category.value, description: description.value, source: props.source, shareAcknowledged: acknowledged.value });
const key = computed(() => { try { return gapIntentKey(gapIntent(build())); } catch { return ''; } });
const same = computed(() => !!key.value && key.value === submittedKey.value);
function changed() { acknowledged.value = false; }
async function submit() {
  if (!valid.value || state.value.phase === 'submitting') return;
  submittedKey.value = key.value;
  const result = await commands.submit(build());
  if (result.status === 'confirmed') emit('submitted', result.value);
}
async function retry() {
  if (!same.value) return;
  const result = await commands.retry();
  if (result.status === 'confirmed') emit('submitted', result.value);
}
onScopeDispose(() => commands.dispose());
</script>

<template>
  <form class="panel" aria-label="提交知识需求" @submit.prevent="submit">
    <KbAlert type="info" :title="gapShareNotice" />
    <p>来源仅表示提交入口，不证明检索故障或全库缺少资料。回答、引用和原会话身份不会附带提交。</p>
    <label>问题类型 <ElSelect v-model="category" aria-label="问题类型" :disabled="state.phase === 'submitting'" @change="changed">
      <ElOption v-for="value in gapCategories" :key="value" :value="value" :label="gapCategoryLabels[value]" />
    </ElSelect></label>
    <label class="gap-description">明确分享的描述
      <ElInput v-model="description" type="textarea" :rows="6" aria-label="明确分享的描述" :disabled="state.phase === 'submitting'" @input="changed" />
    </label>
    <p class="muted">{{ count < 0 ? '文本无效' : `${count} / 4000 个字符` }}；超限须自行缩短，不会静默裁剪。</p>
    <ElCheckbox v-model="acknowledged" :disabled="state.phase === 'submitting'">我已预览并明确确认上述分享与保留范围</ElCheckbox>
    <KbAlert v-if="state.message" type="warning" :title="state.message" />
    <p v-if="state.phase === 'uncertain' && !same" class="muted">输入已修改；请重新确认分享，新的提交使用新请求标识。</p>
    <div class="toolbar">
      <ElButton native-type="submit" type="primary" :disabled="!valid || state.phase === 'submitting' || state.phase === 'confirmed'">提交问题</ElButton>
      <ElButton v-if="state.phase === 'uncertain' && same" @click="retry">按原内容重试</ElButton>
      <ElButton :disabled="state.phase === 'submitting'" @click="emit('cancel')">取消</ElButton>
    </div>
  </form>
</template>

<style scoped>
.gap-description { display: block; margin-top: 20px; }
</style>
