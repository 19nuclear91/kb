<script setup lang="ts">
import { computed, ref, watch } from 'vue';
import { ElButton, ElInput } from 'element-plus';
import { useSession } from '../SessionProvider';
import KbAlert from '../ui/KbAlert.vue';
import KbSpin from '../ui/KbSpin.vue';
import { gapAssignees } from './api';
import { gapStrip } from './input';
import { useGapList } from './hooks';
import type { GapParticipant } from './types';
const props = defineProps<{ gapId: string; disabled?: boolean }>();
const emit = defineEmits<{ selected: [value: GapParticipant | null] }>();
const auth = useSession(), draft = ref(''), submitted = ref(''), active = ref(false), invalid = ref(false);
const loader = computed(() => active.value ? (cursor: string | null, signal: AbortSignal) =>
  gapAssignees(auth.session, props.gapId, { nameQuery: submitted.value, cursor }, signal) : null);
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
    if (active.value) list.reload(); else active.value = true;
  } catch { active.value = false; invalid.value = true; }
}
</script>
<template>
  <section aria-label="处理人候选">
    <p>只显示当前合格的最小处理候选，不读取完整身份目录；实际分派仍重新核验。</p>
    <div class="toolbar"><ElInput v-model="draft" aria-label="处理人名称" placeholder="名称（可留空）" :disabled="disabled" @input="changed" />
      <ElButton :disabled="disabled" @click="search">查询合格处理人</ElButton></div>
    <KbAlert v-if="invalid" type="warning" title="处理人名称输入无效，最多 128 个字符；不会截断。" />
    <div v-else-if="list.loading" role="status"><KbSpin /> 正在读取合格候选…</div>
    <KbAlert v-else-if="list.error" type="warning" :title="list.error" />
    <template v-else-if="list.loaded">
      <p v-if="!list.items.length">当前条件没有合格候选。</p>
      <ul v-else><li v-for="item in list.items" :key="item.userId">{{ item.label ?? '未提供标签' }} · 用户 {{ item.userId }}
        <ElButton :disabled="disabled" @click="emit('selected', item)">选择此负责人</ElButton></li></ul>
      <ElButton v-if="list.hasMore" :disabled="disabled" @click="list.more">读取更多合格候选</ElButton><p v-else class="muted">已到候选末页。</p>
    </template>
  </section>
</template>
