<script setup lang="ts">
import { computed, onScopeDispose, ref, watch } from 'vue';
import { RouterLink } from 'vue-router';
import { ElButton } from 'element-plus';
import { useSession } from '../SessionProvider';
import { useExternalStore } from '../useExternalStore';
import { useAdminAccess } from '../admin/access';
import KbAlert from '../ui/KbAlert.vue';
import KbSpin from '../ui/KbSpin.vue';
import { gapDetail } from './api';
import { useGapResource } from './hooks';
import { gapStateLabels, gapCategoryLabels, gapTerminal } from './types';
import { GapCommands } from './commands';
import GapEntryView from './GapEntryView.vue';
import GapHistoryList from './GapHistoryList.vue';
import GapProcessingForm from './GapProcessingForm.vue';
import GapLifecycleActions from './GapLifecycleActions.vue';
import GapReceptionActions from './GapReceptionActions.vue';

const props = withDefaults(defineProps<{ gapId: string; reception?: boolean }>(), { reception: false });
const auth = useSession(), access = useAdminAccess();
const commands = new GapCommands(auth.session, props.gapId), state = useExternalStore(() => commands), notice = ref(''), inaccessible = ref(false);
watch(() => props.gapId, value => { commands.setScope(value); notice.value = ''; inaccessible.value = false; }, { flush: 'sync' });
onScopeDispose(() => commands.dispose());
const loader = computed(() => inaccessible.value ? null : (signal: AbortSignal) => gapDetail(auth.session, props.gapId, signal));
const resource = useGapResource(loader, () => props.gapId);
watch(() => resource.status, status => { if (status === 404) rejected(status); }, { flush: 'sync' });
// 只消费本人功能资格事实，不依赖空间目录；资格变化立即丢弃表单/绑定并重新核对源问题。
watch([() => access.status, () => access.permissionFactsKey], () => {
  const current = resource.data, actor = auth.state.me?.user.userId;
  commands.clear();
  const stillParticipating = current && (current.reporter.userId === actor || (access.status === 'ready'
    && (access.has('knowledge:gap:read') || (current.assignee?.userId === actor && access.has('knowledge:gap:handle')))));
  if (current && !stillParticipating) {
    notice.value = '当前问题资格已失效或无法确认，已清理原内容和输入。'; inaccessible.value = true;
  } else if (inaccessible.value && access.status === 'ready') {
    inaccessible.value = false;
  } else if (!inaccessible.value) {
    notice.value = '当前资格已变化，正在重新核对问题事实。'; resource.reload();
  }
}, { flush: 'sync' });
function reload() { commands.clear(); notice.value = ''; if (inaccessible.value) inaccessible.value = false; else resource.reload(); }
function confirmed() { commands.clear(); notice.value = '操作已提交，正在重读当前问题事实；不代表通知已送达。'; resource.reload(); }
function rejected(status: number) {
  if (status === 409) { commands.clear(); notice.value = '问题、方案或候选已变化，已清理旧输入，请重读后重新确认。'; resource.reload(); }
  else if (status === 404) { commands.clear(); notice.value = '当前问题不可访问，已清理旧内容，请由当前合格人员核对。'; inaccessible.value = true; }
  else if (status === 403) { commands.clear(); void auth.session.refresh(); }
}
</script>

<template>
  <section class="panel" aria-label="知识问题详情">
    <div class="toolbar"><RouterLink :to="reception ? '/admin/knowledge-gaps' : '/knowledge-gaps'">{{ reception ? '返回受理队列' : '返回本人问题' }}</RouterLink><ElButton :disabled="state.phase === 'submitting'" @click="reload">重新读取问题</ElButton></div>
    <KbAlert v-if="notice" type="info" :title="notice" />
    <KbAlert v-if="state.message" type="warning" :title="state.message" />
    <div v-if="resource.loading" role="status"><KbSpin /> 正在读取问题详情…</div>
    <KbAlert v-else-if="resource.error" type="warning" :title="resource.error" />
    <template v-else-if="resource.data">
      <p>问题 {{ resource.data.gapId }} · {{ gapStateLabels[resource.data.state] }} · {{ gapCategoryLabels[resource.data.category] }}</p>
      <p class="gap-text">{{ resource.data.description }}</p>
      <p>提问者：{{ resource.data.reporter.label ?? '未提供标签' }} <span v-if="resource.data.assignee">· 负责人：{{ resource.data.assignee.label ?? '未提供标签' }}</span></p>
      <KbAlert v-if="reception && resource.data.needsReassignment" type="warning" title="当前需人工重新分派；提示不会自动修改分派或问题状态。" />
      <section v-if="resource.data.currentSolution" aria-label="当前待确认方案"><h2>当前待确认方案</h2>
        <GapEntryView :key="resource.data.currentSolution.entryId" :entry="resource.data.currentSolution" @reference-denied="reload" />
      </section>
      <p class="muted">已分享记录保留；待确认、关闭和撤回均不等于提问者确认解决。</p>
      <p class="muted">从旧通知进入也须核对本次读取的当前状态和方案，不自动确认或恢复旧待办。</p>
      <RouterLink v-if="gapTerminal(resource.data.state)" to="/knowledge-gaps/new">提出新的需求</RouterLink>
      <GapReceptionActions v-if="reception && (resource.data.capabilities.assign || resource.data.capabilities.close)" :detail="resource.data" :commands="commands" @confirmed="confirmed" @rejected="rejected" />
      <GapLifecycleActions v-if="!gapTerminal(resource.data.state)" :detail="resource.data" :commands="commands" @confirmed="confirmed" @rejected="rejected" />
      <GapProcessingForm v-if="resource.data.capabilities.note || resource.data.capabilities.submitSolution" :detail="resource.data" :commands="commands" @confirmed="confirmed" @rejected="rejected" />
      <GapHistoryList :gap-id="resource.data.gapId" @reference-denied="reload" @inaccessible="rejected" />
    </template>
  </section>
</template>

<style scoped>
.gap-text { white-space: pre-wrap; overflow-wrap: anywhere; }
</style>
