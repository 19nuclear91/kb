<script setup lang="ts">
import { computed, onScopeDispose, ref, shallowRef, watch } from 'vue';
import { ElButton, ElInput } from 'element-plus';
import { RouterLink } from 'vue-router';
import { useSession } from '../SessionProvider';
import { useAdminAccess } from '../admin/access';
import PageHeader from '../ui/PageHeader.vue';
import Feedback from '../ui/Feedback.vue';
import SafeText from '../ui/SafeText.vue';
import SpacePicker from '../answerCards/SpacePicker.vue';
import CommandNotice from '../answerCards/CommandNotice.vue';
import { cardApi } from '../answerCards/api';
import { type CommandOutcome } from '../answerCards/commands';
import { useCardCommands } from '../answerCards/useCardCommands';
import { CardError } from '../answerCards/protocol';
import { stewardLabel } from '../answerCards/labels';
import { useCardResource } from '../answerCards/useCardResource';
import type { Person } from '../answerCards/types';
const auth = useSession(), access = useAdminAccess(), space = ref(''), cursor = ref<string | null>(null), selected = ref(''), name = ref(''), candidate = shallowRef<Person | null>(null), months = ref(6), notice = ref('');
const commands = useCardCommands(), key = computed(() => `card:${selected.value}`), pending = computed(() => commands.value.entries.get(key.value));
const list = useCardResource(() => space.value && access.canManageSpace(space.value) ? `${space.value}:${cursor.value ?? ''}` : null,
  signal => cardApi.management(auth.session, { spaceId: space.value, cursor: cursor.value }, signal), denied);
const detail = useCardResource(() => selected.value && access.canManageSpace(space.value) ? `${space.value}:${selected.value}:${access.factsKey ?? ''}` : null,
  signal => cardApi.steward(auth.session, selected.value, signal), denied);
const submitted = shallowRef<{ name: string; cursor: string | null; serial: number } | null>(null); let serial = 0, generation = 0, live = true;
const candidates = useCardResource(() => selected.value && detail.data.value && !detail.data.value.spaceArchived && submitted.value && !pending.value
  ? `${selected.value}:${JSON.stringify(submitted.value)}` : null,
  signal => cardApi.candidates(auth.session, selected.value, submitted.value!.name, submitted.value!.cursor, signal), denied);
function denied() { candidate.value = null; name.value = ''; submitted.value = null; if (selected.value) commands.value.abandon(key.value); access.check(true); }
watch(space, () => { cursor.value = null; selected.value = ''; });
watch([selected, () => auth.state.epoch], () => { generation++; candidate.value = null; name.value = ''; submitted.value = null; notice.value = ''; }, { flush: 'sync' });
watch(() => access.canManageSpace(space.value), allowed => {
  if (!allowed) {
    generation++; candidate.value = null; name.value = ''; submitted.value = null;
    if (access.spaceStatus === 'ready') commands.value.abandon(key.value);
  }
}, { flush: 'sync' });
watch(() => detail.data.value, value => { months.value = value?.stewardship.cycleMonths ?? 6; });
onScopeDispose(() => { live = false; generation++; candidate.value = null; name.value = ''; });
async function reload() {
  const target = key.value, at = generation, epoch = auth.state.epoch;
  candidate.value = null; submitted.value = null;
  const [loaded] = await Promise.allSettled([detail.reload(), list.reload()]);
  if (loaded.status === 'fulfilled' && loaded.value && live && target === key.value && at === generation && epoch === auth.state.epoch) commands.value.acknowledge(target);
}
async function finish(outcome: CommandOutcome, at: number) {
  if (!live || at !== generation) return;
  if (outcome.kind === 'committed') { notice.value = '配置已提交，原复审基准保持。'; await reload(); }
  if (outcome.kind === 'rejected') { notice.value = new CardError(outcome.status ?? 503).message; await reload(); }
}
async function save() {
  const value = detail.data.value; if (!value || pending.value || value.spaceArchived) return;
  const person = candidate.value ?? value.stewardship.responsible;
  if (!person) { notice.value = '请先明确选择合格责任人。'; return; }
  const at = generation;
  await finish(await commands.value.start(key.value, { operation: 'ANSWER_CARD_STEWARDSHIP_UPDATE', cardId: value.cardId, reviewId: null,
    payload: { spaceId: value.spaceId, expectedCardVersion: value.cardVersion, expectedStewardshipVersion: value.stewardship.version, responsibleUserId: person.userId, cycleMonths: Number(months.value) } }), at);
}
async function recover(checking: boolean) { const at = generation; await finish(await (checking ? commands.value.check(key.value) : commands.value.retry(key.value)), at); }
async function abandon() {
  const target = key.value, at = generation, epoch = auth.state.epoch;
  commands.value.abandon(target); await reload();
  if (live && target === key.value && at === generation && epoch === auth.state.epoch) notice.value = detail.data.value
    ? '已明确放弃原意图并重读，原请求仍可能已经提交。' : '已放弃原意图，当前事实未能读取；请重新选择卡片核对后再操作。';
}
</script>
<template>
  <PageHeader title="标准答案责任人管理" description="只显示管理所需身份与状态；改派不重新计算首次指定日" />
  <RouterLink to="/answer-cards">返回标准答案</RouterLink><SpacePicker v-model="space" managed required />
  <ElButton @click="list.reload">重读管理清单</ElButton><Feedback :loading="list.loading.value" :error="list.error.value" />
  <article v-for="card in list.data.value?.items" :key="card.cardId" class="panel"><ElButton @click="selected = card.cardId">管理卡片 {{ card.cardId }}</ElButton>
    <p>{{ card.workflowState }} · {{ stewardLabel[card.stewardship.state] }} · {{ card.stewardship.responsible?.displayName ?? '尚未指定' }}</p></article>
  <ElButton v-if="list.data.value?.hasMore" @click="cursor = list.data.value.nextCursor">下一页管理项</ElButton>
  <CommandNotice :pending="pending" @check="recover(true)" @retry="recover(false)" @abandon="abandon" @refresh="reload" />
  <p v-if="notice" role="status">{{ notice }}</p><Feedback :loading="detail.loading.value" :error="detail.error.value" />
  <section v-if="detail.data.value" class="panel"><h2>卡片 {{ detail.data.value.cardId }} 的责任人</h2>
    <p>{{ stewardLabel[detail.data.value.stewardship.state] }}；首次指定 {{ detail.data.value.stewardship.firstAssignedOn ?? '尚未指定' }}；最近确认 {{ detail.data.value.stewardship.lastConfirmedOn ?? '无' }}；到期 {{ detail.data.value.stewardship.dueDate ?? '无' }}</p>
    <p>改派不重置复审期限；调整周期仍沿用原基准。管理员配置不会授予正文或来源访问。</p>
    <p v-if="detail.data.value.spaceArchived">所属空间已归档，当前仅可读取最小信息。</p>
    <fieldset :disabled="detail.data.value.spaceArchived || !!pending"><legend>选择合格责任人及周期</legend>
      <p>当前：{{ detail.data.value.stewardship.responsible?.displayName ?? '未指定' }}；本次选择：{{ candidate?.displayName ?? '沿用当前人员' }}</p>
      <label>责任人显示名<ElInput v-model="name" aria-label="责任人显示名" /></label><ElButton @click="submitted = { name, cursor: null, serial: ++serial }">查询合格责任人</ElButton>
      <Feedback :loading="candidates.loading.value" :error="candidates.error.value" />
      <p v-for="person in candidates.data.value?.items" :key="person.userId"><SafeText :text="person.displayName" /> · {{ person.userId }} <ElButton @click="candidate = person">选择此责任人</ElButton></p>
      <ElButton v-if="candidates.data.value?.hasMore" @click="submitted = { name: submitted!.name, cursor: candidates.data.value.nextCursor, serial: ++serial }">下一页合格人员</ElButton>
      <label>复审周期<select v-model.number="months"><option v-for="value in [3, 6, 12, 24]" :key="value" :value="value">{{ value }}个月</option></select></label><ElButton @click="save">保存责任人配置</ElButton>
    </fieldset>
  </section>
</template>
