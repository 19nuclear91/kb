<script setup lang="ts">
import { computed, ref, watch } from 'vue';
import { RouterLink, useRoute, useRouter } from 'vue-router';
import { ElButton, ElTag } from 'element-plus';
import { positiveId } from '../api';
import { useSession } from '../SessionProvider';
import { lazyPage } from '../routing/lazyPage';
import { useAdminAccess, perm } from './access';
import { healthCountKeys, healthLabels, healthSignals, type HealthFilter } from './healthApi';
import { useHealthQuery } from './healthQuery';
import PageHeader from '../ui/PageHeader.vue';
import KbAlert from '../ui/KbAlert.vue';

const route = useRoute(), router = useRouter(), auth = useSession(), access = useAdminAccess();
const id = computed(() => String(route.params.spaceId ?? ''));
const valid = computed(() => { try { positiveId(id.value); return true; } catch { return false; } });
const query = useHealthQuery(() => id.value);
const emptyMessage = computed(() => {
  const report = query.data;
  if (!report) return '';
  if (report.affectedDocumentCount === '0') return '当前诊断范围内暂无可见维护信号。';
  if (report.signal !== 'ALL' && report.counts[healthCountKeys[healthSignals.indexOf(report.signal)]] === '0') return '所选类别暂无符合条件的文档，其他类别数量见上方统计。';
  return '本次翻页暂无符合条件的文档，可返回首页查看当前结果。';
});
const choices = computed(() => access.spaceStatus === 'ready' ? access.spaces.filter(space => space.manageable && !space.archived) : []);
const preview = ref<string | null>(null), VersionPreview = lazyPage(() => import('../office/VersionPreview.vue'));
watch(() => query.revision, () => { preview.value = null; }, { flush: 'sync' });
function chooseSpace(event: Event) {
  const target = (event.target as HTMLSelectElement).value;
  if (choices.value.some(space => space.spaceId === target)) void router.push(`/admin/spaces/${target}/health-radar`);
}
function chooseSignal(event: Event) { query.select((event.target as HTMLSelectElement).value as HealthFilter); }
function denied() { preview.value = null; query.previewDenied(); }
</script>

<template>
  <KbAlert v-if="!valid" type="warning" title="目标不可访问" />
  <template v-else-if="auth.state.phase === 'ready' && access.status === 'ready' && access.has(perm.healthRead)">
    <PageHeader title="知识健康雷达" description="按空间查看需要维护的资料"><template #extra><RouterLink :to="`/admin/spaces/${id}`">返回空间管理</RouterLink></template></PageHeader>
    <section class="panel" aria-label="诊断范围">
      <label>知识空间 <select aria-label="知识空间" :value="id" :disabled="access.spaceStatus !== 'ready'" @change="chooseSpace">
        <option v-if="!choices.some(space => space.spaceId === id)" :value="id" disabled>当前空间 {{ id }}</option>
        <option v-for="space in choices" :key="space.spaceId" :value="space.spaceId">{{ space.name }}</option>
      </select></label>
      <p v-if="access.spaceStatus === 'unavailable'" role="alert">空间目录暂不可用。<ElButton @click="access.check(true)">重试空间目录</ElButton></p>
      <p v-else-if="access.spaceStatus === 'ready' && !choices.length">没有可切换的未归档管理空间。</p>
      <p>责任人与复审统计覆盖本空间未删除、未归档的文档；适用期统计仅覆盖本人当前可读的当前发布版本。</p>
      <label>维护信号 <select aria-label="维护信号" :value="query.filter" :disabled="query.loading" @change="chooseSignal">
        <option value="ALL">全部信号</option><option v-for="signal in healthSignals" :key="signal" :value="signal">{{ healthLabels[signal] }}</option>
      </select></label>
      <ElButton :disabled="query.loading" @click="query.refresh">刷新当前筛选</ElButton>
    </section>
    <p v-if="query.loading" role="status">正在读取健康诊断…</p>
    <KbAlert v-else-if="query.error" type="warning" :title="query.error"><template #action>
      <ElButton v-if="query.reason !== 'SPACE_ARCHIVED'" @click="query.retry">{{ query.reason === 'RADAR_DATE_CHANGED' ? '从首页重新查询' : '重试读取' }}</ElButton>
    </template></KbAlert>
    <template v-else-if="query.data">
      <section class="panel" aria-label="维护信号数量">
        <p>统计时间：{{ query.data.asOf }} · 业务日期：{{ query.data.asOfDate }}（{{ query.data.dayTimeZone }}）</p>
        <p>受影响文档：<strong>{{ query.data.affectedDocumentCount }}</strong>。按文档去重，各类数量不可直接相加。</p>
        <div class="health-counts"><button v-for="(signal, index) in healthSignals" :key="signal" type="button" class="health-count" @click="query.select(signal)">
          <span>{{ healthLabels[signal] }}</span><strong>{{ query.data.counts[healthCountKeys[index]] }}</strong>
          <small>{{ index < 4 ? '空间内活动文档' : '当前可读的当前发布版本' }}</small>
        </button></div>
        <p class="muted">复审逾期表示需要重新确认准确性；适用期届满表示版本声明的截止日已过。零信号不代表所有内容均准确或仍然适用。</p>
      </section>
      <section class="panel" aria-label="维护文档清单">
        <h2>需要维护的资料</h2><p>每页最多20份文档，翻页重新读取当前事实。处理后返回或刷新可查看最新结果。</p>
        <p v-if="!query.data.items.length">{{ emptyMessage }}</p>
        <article v-for="item in query.data.items" :key="item.documentId" class="health-item">
          <h3>文档 {{ item.documentId }}</h3>
          <ElTag v-for="signal in item.signals" :key="signal">{{ healthLabels[signal] }}</ElTag>
          <p>责任人：{{ item.stewardship.responsible?.displayName ?? '未指定' }} · 复审到期：{{ item.stewardship.dueDate ?? '未设置' }}</p>
          <p v-if="item.applicability">固定版本 {{ item.applicability.versionId }} · 生效日：{{ item.applicability.effectiveFrom ?? '未声明' }} · 截止日：{{ item.applicability.effectiveUntil }}</p>
          <RouterLink :to="`/admin/documents/${item.documentId}`">管理责任人与复审</RouterLink>
          <template v-if="item.applicability">
            <ElButton @click="preview = `/api/documents/${item.documentId}/versions/${item.applicability.versionId}/preview`">核对此次诊断的版本</ElButton>
            <RouterLink :to="`/documents/${item.documentId}`">打开文档详情处理</RouterLink>
            <p class="muted">文档详情将重新读取当前可访问版本；复审确认由当前合格责任人完成，日期修改需要编辑资格。</p>
          </template>
        </article>
        <ElButton @click="query.refresh">返回首页</ElButton><ElButton v-if="query.data.hasMore" @click="query.next">下一页</ElButton>
      </section>
    </template>
    <VersionPreview v-if="preview && query.data" :path="preview" @close="preview = null" @denied="denied" />
  </template>
</template>

<style scoped>
.health-counts { display: grid; grid-template-columns: repeat(auto-fit, minmax(180px, 1fr)); gap: 12px; }
.health-count { display: grid; gap: 8px; padding: 16px; text-align: left; border: 1px solid var(--el-border-color); border-radius: 8px; background: var(--el-fill-color-blank); color: inherit; cursor: pointer; }
.health-count strong { font-size: 24px; overflow-wrap: anywhere; }
.health-count small { color: var(--el-text-color-secondary); }
.health-item { padding: 16px 0; border-top: 1px solid var(--el-border-color); }
.health-item a, .health-item .el-button, .health-item .el-tag { margin-right: 12px; }
select { margin: 0 16px 0 8px; padding: 6px; }
</style>
