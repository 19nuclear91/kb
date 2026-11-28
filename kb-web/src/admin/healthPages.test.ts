import { h as node, reactive } from 'vue';
import { fireEvent, screen } from '@testing-library/vue';
import { beforeEach, expect, it, vi } from 'vitest';
import { mountRouter } from '../test/app';
import { healthFixture } from '../test/healthFixtures';
import type { useHealthQuery } from './healthQuery';

let query: ReturnType<typeof useHealthQuery>;
const state = reactive({ allowed: true, spaceStatus: 'ready' });
vi.mock('./healthQuery', () => ({ useHealthQuery: () => query }));
vi.mock('../SessionProvider', () => ({ useSession: () => ({ state: { phase: 'ready', epoch: 1 }, session: {} }) }));
vi.mock('./access', async original => ({ ...await original<typeof import('./access')>(), useAdminAccess: () => ({
  status: 'ready', has: () => state.allowed, get spaceStatus() { return state.spaceStatus; }, check: vi.fn(),
  spaces: [{ spaceId: '2', name: '当前空间', manageable: true, archived: false }, { spaceId: '3', name: '另一个空间', manageable: true, archived: false },
    { spaceId: '4', name: '只读空间', manageable: false, archived: false }, { spaceId: '5', name: '归档空间', manageable: true, archived: true }],
}) }));
vi.mock('../office/VersionPreview.vue', () => ({ default: { props: ['path'], emits: ['denied', 'close'], template: '<section aria-label="固定预览">{{ path }}<button @click="$emit(\'denied\')">模拟撤权</button></section>' } }));
import KnowledgeHealthPage from './KnowledgeHealthPage.vue';
beforeEach(() => {
  state.allowed = true; state.spaceStatus = 'ready';
  query = reactive({ loading: false, data: healthFixture(), filter: 'ALL', revision: 1, enabled: true,
    select: vi.fn(), retry: vi.fn(), refresh: vi.fn(), next: vi.fn(), previewDenied: vi.fn() });
});
const show = () => mountRouter(() => node(KnowledgeHealthPage), { path: '/admin/spaces/2/health-radar', routes: [{ path: '/admin/spaces/:spaceId/health-radar', component: KnowledgeHealthPage }, { path: '/:pathMatch(.*)*', component: { template: '<div />' } }] });
it('显示精确数量与ID并集、范围说明和原处理入口', async () => {
  show(); expect(screen.getByText('文档 9223372036854775807')).toBeTruthy();
  expect(screen.getByText(/各类数量不可直接相加/)).toBeTruthy();
  expect(screen.getByRole('link', { name: '管理责任人与复审' }).getAttribute('href')).toBe('/admin/documents/9223372036854775807');
  expect(screen.getByRole('link', { name: '打开文档详情处理' }).getAttribute('href')).toBe('/documents/9223372036854775807');
  expect(screen.queryByRole('option', { name: '只读空间' })).toBeNull(); expect(screen.queryByRole('option', { name: '归档空间' })).toBeNull();
  await fireEvent.update(screen.getByLabelText('维护信号'), 'REVIEW_OVERDUE'); expect(query.select).toHaveBeenCalledWith('REVIEW_OVERDUE');
});
it('预览固定身份且拒绝后清除，不直接提交写入', async () => {
  show(); await fireEvent.click(screen.getByRole('button', { name: '核对此次诊断的版本' }));
  expect((await screen.findByRole('region', { name: '固定预览' })).textContent).toContain('/api/documents/9223372036854775807/versions/99/preview');
  await fireEvent.click(screen.getByRole('button', { name: '模拟撤权' })); expect(query.previewDenied).toHaveBeenCalledOnce();
  expect(screen.queryByRole('region', { name: '固定预览' })).toBeNull();
  expect(screen.queryByRole('button', { name: '确认准确' })).toBeNull();
});
it('错误遮蔽全部旧数据、归档不提供重试，目录失败不遮蔽已确认目标', async () => {
  state.spaceStatus = 'unavailable'; show(); expect(screen.getByText('文档 9223372036854775807')).toBeTruthy();
  expect(screen.getByRole('button', { name: '重试空间目录' })).toBeTruthy();
  query.error = '空间已归档，诊断暂停。'; query.reason = 'SPACE_ARCHIVED'; await fireEvent.click(screen.getByRole('button', { name: '刷新当前筛选' }));
  expect(screen.queryByText('文档 9223372036854775807')).toBeNull(); expect(screen.queryByRole('button', { name: '重试读取' })).toBeNull();
});
it('没有内容资格时只给管理入口，成功空页不承诺全库健康', () => {
  const item = query.data!.items[0]; item.applicability = null; item.signals = ['REVIEW_OVERDUE'];
  show(); expect(screen.queryByRole('button', { name: '核对此次诊断的版本' })).toBeNull();
  expect(screen.queryByRole('link', { name: '打开文档详情处理' })).toBeNull();
  expect(screen.getByText(/零信号不代表所有内容均准确/)).toBeTruthy();
});
it('当前页为空保留空间汇总并提供返回首页，大整数原样显示', () => {
  query.data!.items = []; query.data!.affectedDocumentCount = '9007199254740993';
  query.data!.counts.reviewOverdue = '9007199254740993'; query.data!.counts.applicabilityExpired = '0';
  show(); expect(screen.getByText(/本次翻页暂无符合条件的文档/)).toBeTruthy();
  expect(screen.getAllByText('9007199254740993')).toHaveLength(2);
  expect(screen.getByRole('button', { name: '返回首页' })).toBeTruthy();
});
it('所选类别无项目与空间无可见信号分别表达', async () => {
  query.data!.items = []; query.data!.signal = 'REVIEW_DUE_SOON'; query.filter = 'REVIEW_DUE_SOON';
  show(); expect(screen.getByText(/所选类别暂无符合条件的文档/)).toBeTruthy();
  query.data!.affectedDocumentCount = '0';
  query.data!.counts = { stewardUnassigned: '0', stewardNeedsReassign: '0', reviewOverdue: '0', reviewDueSoon: '0', applicabilityExpired: '0', applicabilityExpiringSoon: '0' };
  await fireEvent.click(screen.getByRole('button', { name: '返回首页' }));
  expect(screen.getByText(/当前诊断范围内暂无可见维护信号/)).toBeTruthy();
});
