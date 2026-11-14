<script setup lang="ts">
import { computed, ref, watch } from 'vue';
import { ElButton, ElInput } from 'element-plus';
import { useSession } from '../SessionProvider';
import KbAlert from '../ui/KbAlert.vue';
import KbSpin from '../ui/KbSpin.vue';
import { gapDocuments } from './api';
import { gapStrip } from './input';
import { useGapList } from './hooks';
import type { GapDocument } from './types';

const props = defineProps<{ gapId: string; disabled?: boolean }>();
const emit = defineEmits<{ selected: [value: GapDocument | null] }>();
const auth = useSession(), draft = ref(''), submitted = ref(''), active = ref(false), invalid = ref(false);
const loader = computed(() => active.value ? (cursor: string | null, signal: AbortSignal) =>
  gapDocuments(auth.session, props.gapId, { nameQuery: submitted.value, cursor }, signal) : null);
const list = useGapList(loader, () => `${props.gapId}:${submitted.value}`);
watch(() => list.error, error => { if (error) emit('selected', null); }, { flush: 'sync' });
function changed() { active.value = false; invalid.value = false; emit('selected', null); }
function search() {
  if (props.disabled) return;
  emit('selected', null);
  try {
    const name = gapStrip(draft.value);
    if ([...name].length > 128) throw new Error('Invalid candidate query');
    submitted.value = name; invalid.value = false;
    if (active.value) list.reload();
    else active.value = true;
  } catch { active.value = false; invalid.value = true; }
}
</script>

<template>
  <section aria-label="方案资料候选">
    <p>只查询你本人当前可读的已发布资料；提交方案时再核对提问者当前资格，不补授权。</p>
    <div class="toolbar"><ElInput v-model="draft" aria-label="资料名称" placeholder="资料名称（可留空）" :disabled="disabled" @input="changed" />
      <ElButton :disabled="disabled" @click="search">查询方案资料</ElButton></div>
    <KbAlert v-if="invalid" type="warning" title="资料名称输入无效，最多 128 个字符；不会截断。" />
    <div v-else-if="list.loading" role="status"><KbSpin /> 正在读取资料候选…</div>
    <KbAlert v-else-if="list.error" type="warning" :title="list.error" />
    <template v-else-if="list.loaded">
      <p v-if="!list.items.length">当前条件没有可用资料候选。</p>
      <ul v-else><li v-for="item in list.items" :key="`${item.documentId}:${item.versionId}`">
        <span>{{ item.title }} · 固定版本 {{ item.versionId }}</span>
        <ElButton :disabled="disabled" @click="emit('selected', item)">选择此固定版本</ElButton>
      </li></ul>
      <ElButton v-if="list.hasMore" :disabled="disabled" @click="list.more">读取更多资料候选</ElButton>
      <p v-else class="muted">已到可用资料候选末页。</p>
    </template>
  </section>
</template>
