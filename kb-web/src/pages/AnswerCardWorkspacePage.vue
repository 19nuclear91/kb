<script setup lang="ts">
import { computed, onScopeDispose, ref, watch } from 'vue';
import { RouterLink, useRoute } from 'vue-router';
import { ElButton, ElInput } from 'element-plus';
import { useSession } from '../SessionProvider';
import { perm, useAdminAccess } from '../admin/access';
import PageHeader from '../ui/PageHeader.vue';
import Feedback from '../ui/Feedback.vue';
import SafeText from '../ui/SafeText.vue';
import CardRevision from '../answerCards/CardRevision.vue';
import ContentEditor from '../answerCards/ContentEditor.vue';
import CommandNotice from '../answerCards/CommandNotice.vue';
import { cardApi, cardText } from '../answerCards/api';
import { type CommandOutcome } from '../answerCards/commands';
import { useCardCommands } from '../answerCards/useCardCommands';
import { CardError } from '../answerCards/protocol';
import { availabilityLabel, stewardLabel } from '../answerCards/labels';
import { useCardResource } from '../answerCards/useCardResource';
import type { Content, Intent, Operation } from '../answerCards/types';
const route = useRoute(), auth = useSession(), access = useAdminAccess();
const id = computed(() => String(route.params.cardId ?? '')), key = computed(() => `card:${id.value}`), commands = useCardCommands();
const pending = computed(() => commands.value.entries.get(key.value)), notice = ref(''), editing = ref(false), reason = ref('');
const allowed = computed(() => access.has(perm.answerCardWrite) || access.has(perm.answerCardReview));
const resource = useCardResource(() => allowed.value ? `${id.value}:${access.permissionFactsKey ?? ''}` : null, signal => cardApi.workspace(auth.session, id.value, signal), denied);
const historyView = ref<'revisions' | 'reviews'>('revisions'), historyOpen = ref(false), cursor = ref<string | null>(null);
const history = useCardResource(() => allowed.value && resource.data.value && historyOpen.value ? `${id.value}:${historyView.value}:${cursor.value ?? ''}` : null,
  signal => cardApi.history(auth.session, id.value, { view: historyView.value, cursor: cursor.value }, signal), denied);
const locked = computed(() => !!pending.value || resource.loading.value);
let live = true, generation = 0;
function denied() { editing.value = false; reason.value = ''; historyOpen.value = false; commands.value.abandon(key.value); access.check(true); }
watch([id, () => auth.state.epoch, allowed], () => { generation++; editing.value = false; reason.value = ''; historyOpen.value = false; cursor.value = null; notice.value = ''; }, { flush: 'sync' });
watch(() => access.permissionFactsKey, () => {
  generation++; editing.value = false; reason.value = ''; historyOpen.value = false;
  const operation = pending.value?.intent.operation;
  if (access.status === 'ready' && operation && !(operation === 'ANSWER_CARD_APPROVE' || operation === 'ANSWER_CARD_REJECT'
    ? access.has(perm.answerCardReview) : access.has(perm.answerCardWrite))) commands.value.abandon(key.value);
}, { flush: 'sync' });
watch(historyView, () => { cursor.value = null; });
onScopeDispose(() => { live = false; generation++; reason.value = ''; });
async function reload() {
  const target = key.value, at = generation, epoch = auth.state.epoch;
  editing.value = false; reason.value = ''; historyOpen.value = false;
  const applied = await resource.reload();
  if (applied && live && target === key.value && at === generation && epoch === auth.state.epoch) commands.value.acknowledge(target);
}
async function finish(outcome: CommandOutcome, at: number, target: string) {
  const active = () => live && at === generation && target === id.value;
  if (!active()) return;
  if (outcome.kind === 'committed') {
    notice.value = '操作已提交，正在读取当前事实。'; await reload();
    if (active()) notice.value = resource.data.value ? '操作已提交，已读取当前事实。' : '操作已提交，当前事实未能读取；请主动重读。';
  }
  if (outcome.kind === 'rejected') {
    notice.value = new CardError(outcome.status ?? 503).message; await reload();
    if (active() && [401, 403, 404].includes(outcome.status ?? 0)) denied();
  }
}
async function send(operation: Operation, content?: Content) {
  const card = resource.data.value;
  if (!card || locked.value) return;
  const target = id.value, at = generation;
  try {
    const payload: Record<string, unknown> = { spaceId: card.spaceId, expectedCardVersion: card.cardVersion };
    const intent: Intent = { operation, cardId: card.cardId, reviewId: null, payload };
    if (operation === 'ANSWER_CARD_CONFIRM') Object.assign(payload, { revisionId: card.published!.revisionId, expectedStewardshipVersion: card.stewardship.version });
    else {
      Object.assign(payload, { branchId: card.draft?.branchId ?? null, revisionId: (card.draft ?? card.published)!.revisionId });
      if (operation === 'ANSWER_CARD_SAVE') payload.content = content;
      else if (operation !== 'ANSWER_CARD_SUBMIT') {
        intent.reviewId = card.review!.reviewId; payload.expectedReviewVersion = card.review!.version;
        if (operation === 'ANSWER_CARD_REJECT') payload.reason = cardText(reason.value, 1, 2000);
      }
    }
    await finish(await commands.value.start(key.value, intent), at, target);
  } catch (error) { if (live && at === generation) notice.value = error instanceof CardError ? error.message : '操作未能发出，请重新核对。'; }
}
async function recover(checking: boolean) { const at = generation, target = id.value; await finish(await (checking ? commands.value.check(key.value) : commands.value.retry(key.value)), at, target); }
async function abandon() {
  const target = key.value, at = generation, epoch = auth.state.epoch;
  commands.value.abandon(target); await reload();
  if (live && target === key.value && at === generation && epoch === auth.state.epoch) notice.value = resource.data.value
    ? '已放弃待核对意图并重读；原请求可能已经提交。' : '已放弃待核对意图，当前事实未能读取；请重读后再操作。';
}
function contentDenied() { resource.clear(); history.clear(); denied(); notice.value = '当前内容不可访问，请重新核对资格。'; }
</script>
<template>
  <PageHeader title="标准答案工作区" description="维护、独立审核与本人周期复审" />
  <RouterLink to="/answer-cards/workspace">返回工作清单</RouterLink><ElButton @click="reload">重读当前事实</ElButton>
  <CommandNotice :pending="pending" @check="recover(true)" @retry="recover(false)" @abandon="abandon" @refresh="reload" />
  <p v-if="notice" role="status">{{ notice }}</p><Feedback :loading="resource.loading.value" :error="resource.error.value" />
  <template v-if="resource.data.value">
    <section class="panel"><h2>当前状态</h2>
      <p>内容审核：{{ resource.data.value.review ? '正在审核精确修订' : resource.data.value.draft ? '有待处理草稿' : '已有批准发布记录' }}</p>
      <p>发布版展示：{{ resource.data.value.published ? availabilityLabel[resource.data.value.published.availability] : '没有当前可访问的发布版' }}</p>
      <p>{{ stewardLabel[resource.data.value.stewardship.state] }}；到期日 {{ resource.data.value.stewardship.dueDate ?? '尚未指定' }}</p>
      <p>来源变化需要保存新稿并重新审核；周期确认不能解除此类暂停，也不能延长有效期。</p>
      <ElButton v-if="resource.data.value.capabilities.canEdit" :disabled="locked" @click="editing = !editing">{{ editing ? '关闭草稿编辑' : '编辑新草稿' }}</ElButton>
      <ElButton v-if="resource.data.value.capabilities.canSubmit" :disabled="locked" @click="send('ANSWER_CARD_SUBMIT')">送审此草稿</ElButton>
      <ElButton v-if="resource.data.value.capabilities.canConfirm" :disabled="locked" @click="send('ANSWER_CARD_CONFIRM')">确认当前发布版仍准确</ElButton>
    </section>
    <ContentEditor v-if="editing && resource.data.value.capabilities.canEdit" :initial="resource.data.value.draft ?? resource.data.value.published" :space-id="resource.data.value.spaceId" :locked="locked" @save="send('ANSWER_CARD_SAVE', $event)" @denied="contentDenied" />
    <section v-if="resource.data.value.review" class="panel"><h2>审核轮次 {{ resource.data.value.review.roundNo }}</h2>
      <p>固定修订ID {{ resource.data.value.review.revisionId }}；轮次ID {{ resource.data.value.review.reviewId }}。审核中草稿冻结。</p>
      <p v-if="!resource.data.value.capabilities.canReview">当前不能自行审批。分支所有贡献者及本轮送审者均不得批准或驳回。</p>
      <template v-if="resource.data.value.capabilities.canReview"><ElButton :disabled="locked" @click="send('ANSWER_CARD_APPROVE')">批准此轮并发布</ElButton>
        <label>驳回原因<ElInput v-model="reason" type="textarea" :disabled="locked" aria-label="驳回原因" /></label><ElButton :disabled="locked" @click="send('ANSWER_CARD_REJECT')">驳回此轮</ElButton></template>
      <ElButton v-if="resource.data.value.capabilities.canWithdraw" :disabled="locked" @click="send('ANSWER_CARD_WITHDRAW')">撤回本人送审</ElButton>
    </section>
    <h2 v-if="resource.data.value.draft">活动草稿（尚未发布）</h2><CardRevision v-if="resource.data.value.draft" :value="resource.data.value.draft" private-view @denied="contentDenied" />
    <h2 v-if="resource.data.value.published">当前发布记录</h2><CardRevision v-if="resource.data.value.published" :value="resource.data.value.published" private-view @denied="contentDenied" />
    <ElButton @click="historyOpen = !historyOpen">{{ historyOpen ? '关闭私有历史' : '查看私有历史' }}</ElButton>
    <section v-if="historyOpen"><label>历史类型<select v-model="historyView"><option value="revisions">内容修订</option><option value="reviews">全部审核轮次</option></select></label>
      <Feedback :loading="history.loading.value" :error="history.error.value" />
      <article v-for="entry in history.data.value?.items" :key="entry.revision?.revisionId ?? entry.review!.reviewId" class="panel">
        <CardRevision v-if="entry.revision" :value="entry.revision" private-view @denied="contentDenied" />
        <p v-if="entry.review">轮次 {{ entry.review.roundNo }} · {{ entry.review.state }} · 修订 {{ entry.review.revisionId }}</p>
        <SafeText v-if="entry.review?.reason" :text="entry.review.reason" />
      </article><ElButton v-if="history.data.value?.hasMore" @click="cursor = history.data.value.nextCursor">下一页历史</ElButton>
    </section>
  </template>
</template>
