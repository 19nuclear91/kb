import { beforeEach, describe, expect, it, vi } from 'vitest';
import { nextTick, reactive, type Component } from 'vue';
import { fireEvent, screen, within } from '@testing-library/vue';
import { renderHost } from '../test/render';
import { ApiError } from '../api';
import { aiEvent, aiMeta, aiOverviewData, aiPageData } from '../test/aiUsageFixtures';
import type { AiQuery } from './aiUsageQuery';
const h = reactive({ epoch: 1, phase: 'ready', allowed: true });
vi.mock('../SessionProvider', () => ({ useSession: () => ({ get state() { return { epoch: h.epoch, phase: h.phase }; } }) }));
vi.mock('./access', () => ({ useAdminAccess: () => ({ status: 'ready', has: () => h.allowed, permissionFactsKey: 'ai' }) }));
vi.mock('./aiUsageQuery', () => ({ useAiUsageQuery: vi.fn(() => query) }));
import AiAuditsPage from './AiAuditsPage.vue';
import AiUsagePage from './AiUsagePage.vue';
import { useAiUsageQuery } from './aiUsageQuery';
let query: AiQuery;
beforeEach(() => {
  h.epoch++; h.phase = 'ready'; h.allowed = true;
  query = reactive({ key: 'unit', filters: { limit: 20, groupBy: 'day' }, items: aiPageData('usage').items, metadata: aiMeta, overview: aiOverviewData(), cursor: 'cursor_A', hasMore: true, loading: false, forbidden: false, detail: null, submit: vi.fn(), refresh: vi.fn(), reset: vi.fn(), more: vi.fn(), retry: vi.fn(), open: vi.fn(), close: vi.fn() }) as AiQuery;
  vi.mocked(useAiUsageQuery).mockClear();
});
const fill = (label: string, value: string) => fireEvent.update(screen.getByLabelText(label), value);
const submit = () => fireEvent.submit(screen.getByRole('form', { name: 'AI 查询筛选' }));
async function render(page: Component) {
  const view = renderHost(page); await nextTick(); return view;
}
describe('AI 页面组件', () => {
  it('草稿不改变刷新与续页，只有显式提交才发起新查询', async () => {
    await render(AiUsagePage); await fill('用户 ID', '9007199254740993'); await fill('分组方式', 'department'); await fill('每次条数', '50'); expect(query.submit).not.toHaveBeenCalled(); await fireEvent.click(screen.getByRole('button', { name: '继续读取' })); expect(query.more).toHaveBeenCalledTimes(1); await fireEvent.click(screen.getByRole('button', { name: '刷新查询' })); expect(query.refresh).toHaveBeenCalledTimes(1); await submit(); expect(query.submit).toHaveBeenCalledWith(expect.objectContaining({ userId: '9007199254740993', groupBy: 'department', limit: 50 }));
  });
  it('非法 ID 或窗口在请求前提示，不替换已有总览', async () => {
    await render(AiUsagePage); await fill('用户 ID', '01'); await submit(); expect(query.submit).not.toHaveBeenCalled(); expect(screen.getByText('输入或请求不符合要求。')).toBeTruthy(); expect(screen.getByRole('region', { name: '用量总览' })).toBeTruthy(); await fill('用户 ID', ''); await fill('时间范围', 'custom'); await fill('起始时间（含）', '2026-10-20T12:00'); await submit(); expect(query.submit).not.toHaveBeenCalled(); await fill('截止时间（不含）', '2026-10-21T12:00'); await submit(); expect(query.submit).toHaveBeenCalledTimes(1);
  });
  it('真实 token 没有已知样本与已知零分开，大整数和计划确认各自展示', async () => {
    query.overview!.metrics.promptTokens = null; query.overview!.metrics.completionTokens = '0'; query.overview!.metrics.plannedChargedTokens = '18446744073709551617'; await render(AiUsagePage); const region = screen.getByRole('region', { name: '用量总览' }); expect(within(region).getByText('无已知样本')).toBeTruthy(); expect(within(region).getByText('18446744073709551617')).toBeTruthy(); expect(within(region).getByText('15')).toBeTruthy(); expect(within(region).getAllByText('0').length).toBeGreaterThan(0); expect(screen.getByText(/跨页不代表同一冻结快照/)).toBeTruthy(); expect(region.querySelector('time')?.dateTime).toBe(aiMeta.asOf);
  });
  it('未启用、覆盖缺口和首次故障不会伪装为完整零历史', async () => {
    query.metadata = { ...aiMeta, collectionEnabled: false, collectionStatus: 'DISABLED', coverageStartedAt: null, historyMayBeIncomplete: true }; await render(AiUsagePage); expect(screen.getByText(/尚未开始采集 AI 用量/)).toBeTruthy(); expect(screen.getByText(/此时间范围的历史可能不完整/)).toBeTruthy(); query.metadata = undefined; query.overview = undefined; query.error = new ApiError(503).message; query.status = 503; await nextTick(); expect(screen.queryByRole('region', { name: '用量总览' })).toBeNull(); expect(screen.getByRole('button', { name: '重试读取' })).toBeTruthy();
  });
  it('同调用多事件按事件身份展示，详情仅显示安全文本和 UNKNOWN 空值', async () => {
    query.items = [aiEvent(), aiEvent(2)]; query.overview = undefined; query.detail = { eventId: aiEvent().eventId, loading: false, data: { ...aiEvent(), snapshot: { ...aiEvent().snapshot, resultCode: '<img src=x onerror=alert(1)>' } } }; await render(AiAuditsPage); expect(screen.getByText('已加载 2 条事件')).toBeTruthy(); const dialog = await screen.findByRole('dialog'); expect(within(dialog).getAllByText('未知（null）')).toHaveLength(2); expect(within(dialog).getByText('<img src=x onerror=alert(1)>')).toBeTruthy(); expect(dialog.querySelector('img')).toBeNull(); expect(within(dialog).getByText('9007199254740993')).toBeTruthy(); await fireEvent.click(screen.getByRole('button', { name: `查看事件 ${aiEvent().eventId}` })); expect(query.open).toHaveBeenCalledWith(aiEvent().eventId);
  });
  it('事件 404 不复用旧详情，403 清理草稿且显式恢复', async () => {
    query.items = [aiEvent()]; query.detail = { eventId: aiEvent().eventId, loading: false, status: 404, error: new ApiError(404).message }; await render(AiAuditsPage); expect(within(await screen.findByRole('dialog')).getByText('该事件不存在或已清理。')).toBeTruthy(); await fill('调用 ID', aiEvent().callId); query.forbidden = true; query.items = []; query.detail = null; query.metadata = undefined; query.status = 403; query.error = new ApiError(403).message; await nextTick(); expect((screen.getByLabelText('调用 ID') as HTMLInputElement).value).toBe(''); await fireEvent.click(screen.getByRole('button', { name: '重新读取 AI 数据' })); expect(query.retry).toHaveBeenCalledTimes(1);
  });
  it('身份与权限变化卸载资料，不启动无资格查询', async () => {
    query.items = [aiEvent()]; h.allowed = false; await render(AiAuditsPage); expect(useAiUsageQuery).not.toHaveBeenCalled(); h.allowed = true; await nextTick(); await fill('调用 ID', aiEvent().callId); h.phase = 'loading'; await nextTick(); expect(screen.queryByLabelText('调用 ID')).toBeNull(); h.epoch++; h.phase = 'ready'; await nextTick(); expect((screen.getByLabelText('调用 ID') as HTMLInputElement).value).toBe('');
  });
  it('查询条件和详情不写浏览器存储或地址', async () => {
    query.items = [aiEvent()]; const save = vi.spyOn(Storage.prototype, 'setItem'), push = vi.spyOn(window.history, 'pushState'), replace = vi.spyOn(window.history, 'replaceState'); await render(AiAuditsPage); await fill('调用 ID', aiEvent().callId); await submit(); await fireEvent.click(screen.getByRole('button', { name: `查看事件 ${aiEvent().eventId}` })); expect(save).not.toHaveBeenCalled(); expect(push).not.toHaveBeenCalled(); expect(replace).not.toHaveBeenCalled();
  });
});
