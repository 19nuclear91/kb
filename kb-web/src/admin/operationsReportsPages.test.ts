import { h as node, nextTick, reactive } from 'vue';
import { fireEvent, screen, within } from '@testing-library/vue';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import { mountRouter } from '../test/app';
import { operationsReportData, unknownComments } from '../test/operationsReportsFixtures';
import type { useOperationsReportsQuery } from './operationsReportsQuery';
const h = reactive({ ai: false, report: true });
let query: ReturnType<typeof useOperationsReportsQuery>;
vi.mock('./operationsReportsQuery', () => ({ useOperationsReportsQuery: () => query }));
vi.mock('./access', async original => ({ ...await original<typeof import('./access')>(), useAdminAccess: () => ({ status: 'ready', has: (code: string) => code === 'ai:usage:read' ? h.ai : h.report }) }));
import OperationsReportsPage from './OperationsReportsPage.vue';
beforeEach(() => {
  h.ai = false; h.report = true; query = reactive({ key: '1', resetKey: '1', filters: {}, loading: false, forbidden: false, data: operationsReportData(), submit: vi.fn(), reset: vi.fn(), refresh: vi.fn(), retry: vi.fn() });
});
const fill = (label: string, value: string) => fireEvent.update(screen.getByLabelText(label), value);
const render = () => mountRouter(() => node(OperationsReportsPage));
describe('运营报表页面', () => {
  it('显式提交、刷新、重置且默认最近 7 天，不按输入即时读取', async () => {
    render(); expect(screen.getByText(/默认最近 7 天/)).toBeTruthy(); await fill('开始时间', '2026-09-01T00:00:00Z'); await fill('结束时间', '2026-10-02T00:00:00Z'); expect(query.submit).not.toHaveBeenCalled(); await fireEvent.click(screen.getByRole('button', { name: '查询' })); expect(query.submit).toHaveBeenCalledWith({ from: '2026-09-01T00:00:00Z', to: '2026-10-02T00:00:00Z' }); await fireEvent.click(screen.getByRole('button', { name: '刷新已提交条件' })); expect(query.refresh).toHaveBeenCalledTimes(1); await fireEvent.click(screen.getByRole('button', { name: '重置为最近 7 天' })); expect(query.reset).toHaveBeenCalledTimes(1); expect((screen.getByLabelText('开始时间') as HTMLInputElement).value).toBe(''); expect(within(screen.getByRole('region', { name: '当前库存' })).getByText(/不是所选窗口内新增量或历史库存/)).toBeTruthy();
  });
  it('31 天多一个微秒或单边时间不提交', async () => {
    render(); await fill('开始时间', '2026-09-01T00:00:00Z'); await fireEvent.click(screen.getByRole('button', { name: '查询' })); expect(query.submit).not.toHaveBeenCalled(); await fill('结束时间', '2026-10-02T00:00:00.000001Z'); await fireEvent.click(screen.getByRole('button', { name: '查询' })); expect(query.submit).not.toHaveBeenCalled();
  });
  it('无损显示大整数、覆盖未知、真实零与无有效时长样本', () => {
    query.data = unknownComments(); query.data.inventory.documentCount = '90071992547409930000000001'; query.data.review.durationSampleCount = '0'; query.data.review.meanDecisionMs = null; render(); expect(screen.getByText('90071992547409930000000001')).toBeTruthy(); expect(screen.getByText(/平均处理耗时（毫秒）：无有效样本/)).toBeTruthy(); expect(within(screen.getByRole('region', { name: '评论' })).getAllByText('—（历史未覆盖）')).toHaveLength(2); expect(within(screen.getByRole('region', { name: '审核' })).getByText('未知时长样本数').nextElementSibling?.textContent).toBe('0'); expect(screen.getByRole('region', { name: 'UTC 日趋势' })).toBeTruthy();
  });
  it('部分覆盖与部分日显式显示，故障遮蔽旧结果', async () => {
    query.data!.comments.coverage.status = 'PARTIAL'; query.data!.days[0].partialDay = true; render(); expect(screen.getByText('部分覆盖：仅为已观察部分')).toBeTruthy(); expect(screen.getByText('部分日')).toBeTruthy(); Object.assign(query, { status: 503, error: '服务暂不可用' }); await nextTick(); expect(screen.queryByRole('region', { name: '当前库存' })).toBeNull(); await fireEvent.click(screen.getByRole('button', { name: '重试读取' })); expect(query.retry).toHaveBeenCalledTimes(1); query.loading = true; await nextTick(); expect(screen.getByRole('status')).toBeTruthy();
  });
  it('只在独立 AI 权限确认后显示既有入口', async () => {
    render(); expect(screen.queryByRole('link', { name: '查看 AI 用量' })).toBeNull(); h.ai = true; await nextTick(); expect(screen.getByRole('link', { name: '查看 AI 用量' }).getAttribute('href')).toBe('/admin/ai/usage');
  });
  it('身份、权限或隐藏清理时清空未提交筛选和错误', async () => {
    render(); await fill('开始时间', 'private-invalid'); await fireEvent.click(screen.getByRole('button', { name: '查询' })); expect(screen.getByRole('alert')).toBeTruthy(); query.resetKey = '2'; await nextTick(); expect((screen.getByLabelText('开始时间') as HTMLInputElement).value).toBe(''); expect(screen.queryByRole('alert')).toBeNull();
  });
});
