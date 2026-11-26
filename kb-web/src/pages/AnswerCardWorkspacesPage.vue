<script setup lang="ts">
import { computed, onScopeDispose, ref, watch } from 'vue';
import { RouterLink, useRouter } from 'vue-router';
import { ElButton } from 'element-plus';
import { useSession } from '../SessionProvider';
import { perm, useAdminAccess } from '../admin/access';
import PageHeader from '../ui/PageHeader.vue';
import Feedback from '../ui/Feedback.vue';
import SafeText from '../ui/SafeText.vue';
import SpacePicker from '../answerCards/SpacePicker.vue';
import ContentEditor from '../answerCards/ContentEditor.vue';
import CommandNotice from '../answerCards/CommandNotice.vue';
import { cardApi } from '../answerCards/api';
import { cardCommands, type CommandOutcome } from '../answerCards/commands';
import { useCardCommands } from '../answerCards/useCardCommands';
import { stewardLabel } from '../answerCards/labels';
import { CardError } from '../answerCards/protocol';
import { useCardResource } from '../answerCards/useCardResource';
import type { Content } from '../answerCards/types';
const auth = useSession(), access = useAdminAccess(), router = useRouter();
const view = ref(access.has(perm.answerCardWrite) ? 'editable' : 'reviewable'), space = ref(''), cursor = ref<string | null>(null), creating = ref(false), createSpace = ref(''), notice = ref('');
createSpace.value = [...cardCommands(auth.session).entries.keys()].find(value => value.startsWith('create:'))?.slice(7) ?? '';
const commands = useCardCommands(), key = computed(() => `create:${createSpace.value}`), pending = computed(() => commands.value.entries.get(key.value));
const allowed = computed(() => access.has(view.value === 'reviewable' ? perm.answerCardReview : perm.answerCardWrite));
const needsRefresh = ref(false);
const result = useCardResource(() => allowed.value ? `${view.value}:${space.value}:${cursor.value ?? ''}:${access.permissionFactsKey ?? ''}` : null,
  signal => cardApi.workspaces(auth.session, { view: view.value, spaceId: space.value || null, cursor: cursor.value }, signal), () => access.check(true));
const createLocked = computed(() => !!pending.value || result.loading.value || !result.data.value || needsRefresh.value);
watch(() => result.data.value, value => { if (value) needsRefresh.value = false; });
watch([view, space], () => { cursor.value = null; });
watch([() => auth.state.epoch, () => access.has(perm.answerCardWrite)], ([epoch], [previousEpoch]) => {
  if (access.status === 'ready' && !access.has(perm.answerCardWrite)) commands.value.abandon(key.value);
  creating.value = false; notice.value = '';
  if (epoch !== previousEpoch || access.status === 'ready' && !access.has(perm.answerCardWrite)) createSpace.value = '';
}, { flush: 'sync' });
let live = true;
onScopeDispose(() => { live = false; });
async function finish(outcome: CommandOutcome, originalKey: string, epoch = auth.state.epoch) {
  if (!live || originalKey !== key.value || epoch !== auth.state.epoch) return;
  if (outcome.kind === 'committed' && outcome.receipt) {
    commands.value.acknowledge(originalKey); creating.value = false;
    await router.push(`/answer-cards/${outcome.receipt.cardId}/workspace`);
  } else if (outcome.kind === 'rejected') { creating.value = false; needsRefresh.value = true; notice.value = new CardError(outcome.status ?? 503).message; access.check(true); }
}
async function save(content: Content) {
  if (createLocked.value) return;
  const originalKey = key.value, epoch = auth.state.epoch;
  await finish(await commands.value.start(originalKey, { operation: 'ANSWER_CARD_CREATE', cardId: null, reviewId: null, payload: { spaceId: createSpace.value, content } }), originalKey, epoch);
}
async function recover(checking: boolean) { const originalKey = key.value, epoch = auth.state.epoch; await finish(await (checking ? commands.value.check(originalKey) : commands.value.retry(originalKey)), originalKey, epoch); }
async function abandon() {
  const originalKey = key.value, epoch = auth.state.epoch;
  commands.value.abandon(originalKey); creating.value = false; needsRefresh.value = true;
  const applied = await result.reload();
  if (live && originalKey === key.value && epoch === auth.state.epoch) notice.value = applied
    ? '已放弃原意图并重读；原请求仍可能已经提交，请先核对清单。' : '已放弃原意图，清单尚未读取成功；请重读工作清单后再新建。';
}
</script>
<template>
  <PageHeader title="标准答案维护与审核" description="按当前资格核对发布版、草稿、依据和周期复审" />
  <RouterLink to="/answer-cards">返回标准答案</RouterLink><RouterLink v-if="access.spaces.length || access.has(perm.spaceManageAll)" to="/answer-cards/management">责任人管理</RouterLink>
  <label>工作视图<select v-model="view"><option v-if="access.has(perm.answerCardWrite)" value="editable">可编辑</option><option v-if="access.has(perm.answerCardReview)" value="reviewable">可审核</option><option v-if="access.has(perm.answerCardWrite)" value="mine">本人负责</option><option v-if="access.has(perm.answerCardWrite)" value="attention">本人需处理</option></select></label>
  <SpacePicker v-model="space" /><ElButton @click="cursor = null; result.reload()">重读工作清单</ElButton>
  <ElButton v-if="access.has(perm.answerCardWrite)" :disabled="createLocked" @click="creating = !creating">{{ creating ? '关闭新建编辑' : '新建标准答案' }}</ElButton>
  <section v-if="access.has(perm.answerCardWrite)"><SpacePicker v-model="createSpace" required :disabled="!!pending" />
    <CommandNotice :pending="pending" @check="recover(true)" @retry="recover(false)" @abandon="abandon" @refresh="pending?.receipt && finish({ kind: 'committed', receipt: pending.receipt }, key)" />
    <ContentEditor v-if="creating && createSpace" :key="createSpace" :initial="null" :space-id="createSpace" :locked="createLocked" @save="save" @denied="creating = false; access.check(true)" />
  </section>
  <p v-if="notice" role="status">{{ notice }}</p><Feedback :loading="result.loading.value" :error="result.error.value" />
  <p v-if="result.data.value && !result.data.value.items.length">当前没有此视图中的卡片。</p>
  <article v-for="card in result.data.value?.items" :key="card.cardId" class="panel"><RouterLink :to="`/answer-cards/${card.cardId}/workspace`"><SafeText :text="(card.draft ?? card.published)!.question" /></RouterLink>
    <p>{{ card.review ? '审核中' : card.draft ? '有活动草稿' : '已发布' }} · {{ stewardLabel[card.stewardship.state] }}</p></article>
  <ElButton v-if="result.data.value?.hasMore" @click="cursor = result.data.value.nextCursor">下一页工作项</ElButton>
</template>
