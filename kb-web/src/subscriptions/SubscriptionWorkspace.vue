<script setup lang="ts">
import { computed, ref } from 'vue';
import { ElButton, ElInput, ElOption, ElSelect } from 'element-plus';
import { useSession } from '../SessionProvider';
import KbAlert from '../ui/KbAlert.vue';
import * as api from './api';
import { useCommand, usePage, useRead } from './hooks';
import { kinds, modes, type Kind, type Mode, type Candidate, type Subscription } from './types';
import CommandStatus from './CommandStatus.vue';

const props = defineProps<{ documentId?: string }>(), auth = useSession();
const kind = ref<Kind>('DOCUMENT'), search = ref(''), selectedMode = ref<Mode>(), submitted = ref<{ kind: Kind; text: string }>();
const list = usePage((cursor, signal) => api.subscriptions(auth.session, cursor, signal), 'subscriptions');
const preference = useRead((signal: AbortSignal) => api.preference(auth.session, signal), 'preference');
const target = useRead(computed(() => props.documentId ? (signal: AbortSignal) => api.target(auth.session, props.documentId!, signal) : null), () => props.documentId ?? '');
const candidates = usePage(computed(() => (cursor: string | null, signal: AbortSignal) => submitted.value
  ? api.candidates(auth.session, submitted.value.kind, submitted.value.text, cursor, signal)
  : Promise.resolve({ items: [] as Candidate[], hasMore: false, nextCursor: null, asOf: new Date().toISOString() })), () => JSON.stringify(submitted.value));
function reload() { list.reload(); preference.reload(); target.reload(); candidates.reload(); selectedMode.value = undefined; }
const command = useCommand(reload, reload);
function subscribe(value: Candidate) { command.submit(api.subscribeIntent(value)); }
function cancel(value: Subscription | Candidate) {
  if (value.subscriptionId && value.generationId) command.submit(api.cancelIntent({ subscriptionId: value.subscriptionId, generationId: value.generationId, version: value.version }));
}
function savePreference() {
  if (preference.data && selectedMode.value && selectedMode.value !== preference.data.mode) command.submit(api.preferenceIntent(selectedMode.value, preference.data.version));
}
</script>
<template>
  <p>订阅只记录生效后的正式发布。文档、空间和标签重叠命中同一次发布时只提醒一次；最多保留 200 条有效订阅。收藏不会自动变成订阅。</p>
  <CommandStatus :command="command" />
  <section class="panel" aria-label="提醒频率">
    <h2>提醒频率</h2>
    <KbAlert v-if="preference.error" type="warning" :title="preference.error" />
    <p v-else-if="preference.loading" role="status">正在核对频率…</p>
    <template v-else-if="preference.data">
      <p>当前设置：{{ modes[preference.data.mode] }}。每周一北京时间 09:00 起汇总上一周周一零点至本周一零点的更新。</p>
      <ElSelect v-model="selectedMode" :disabled="command.blocked" placeholder="选择新频率" aria-label="新提醒频率" style="width: 180px"><ElOption v-for="(label, value) in modes" :key="value" :label="label" :value="value" /></ElSelect>
      <ElButton :disabled="command.blocked || !selectedMode || selectedMode === preference.data.mode" @click="savePreference">确认修改频率</ElButton>
      <p>切换只影响之后发布，已有待提醒项沿用原频率。没有当前可读更新时不发空周报。</p>
    </template>
  </section>
  <section v-if="documentId" class="panel" aria-label="当前文档订阅">
    <h2>当前文档</h2>
    <KbAlert v-if="target.error" type="warning" :title="target.error" /><p v-else-if="target.loading">正在核对文档订阅资格…</p>
    <template v-else-if="target.data"><p>{{ target.data.label }}</p>
      <ElButton v-if="target.data.state === 'ACTIVE'" :disabled="command.blocked" @click="cancel(target.data)">取消当前文档订阅</ElButton>
      <ElButton v-else :disabled="command.blocked" @click="subscribe(target.data)">确认订阅当前文档</ElButton>
    </template>
  </section>
  <section class="panel" aria-label="选择订阅目标">
    <h2>添加订阅</h2>
    <div class="toolbar">
      <ElSelect v-model="kind" aria-label="目标类型" style="width: 120px"><ElOption v-for="(label, value) in kinds" :key="value" :label="label" :value="value" /></ElSelect>
      <ElInput v-model="search" aria-label="目标名称" placeholder="按名称查找" :maxlength="128" style="width: 240px" />
      <ElButton :disabled="command.blocked" @click="submitted = { kind, text: search }">查找可订阅目标</ElButton>
    </div>
    <KbAlert v-if="candidates.error" type="warning" :title="candidates.error" />
    <p v-else-if="candidates.loading" role="status">正在查找…</p>
    <template v-else-if="submitted && candidates.data">
      <p v-if="!candidates.data.items.length">当前没有匹配的可订阅目标。</p>
      <ul><li v-for="item in candidates.data.items" :key="`${item.kind}:${item.targetId}`">{{ kinds[item.kind] }} · {{ item.label }}
        <ElButton :disabled="command.blocked || item.state === 'ACTIVE' || !item.enabled" @click="subscribe(item)">{{ item.state === 'ACTIVE' ? '已订阅' : item.state === 'CANCELLED' ? '确认重新订阅' : '确认订阅' }}</ElButton>
      </li></ul>
      <ElButton v-if="candidates.data.hasMore" @click="candidates.more">下一页候选</ElButton>
    </template>
  </section>
  <section class="panel" aria-label="本人订阅列表">
    <div class="toolbar"><h2>我的订阅</h2><ElButton @click="reload">重新读取当前状态</ElButton></div>
    <p>取消会停止原代次尚未发出的提醒，其他原命中订阅仍可支持提醒。已有更新与通知保留；重新订阅建立新代次，不补发取消期间的更新。</p>
    <KbAlert v-if="list.error" type="warning" :title="list.error" /><p v-else-if="list.loading" role="status">正在读取订阅…</p>
    <template v-else-if="list.data">
      <p v-if="!list.data.items.length">还没有订阅关系。</p>
      <ul><li v-for="item in list.data.items" :key="item.subscriptionId">
        {{ kinds[item.kind] }} · {{ item.available ? item.label : '目标当前不可用' }} · {{ item.state === 'ACTIVE' ? '订阅中' : '已取消' }}
        <span v-if="item.enabled === false">（标签已停用，原订阅保留）</span>
        <ElButton v-if="item.state === 'ACTIVE'" :disabled="command.blocked" @click="cancel(item)">确认取消订阅</ElButton>
      </li></ul><ElButton v-if="list.data.hasMore" @click="list.more">下一页订阅</ElButton>
    </template>
  </section>
</template>
