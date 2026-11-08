import { beforeEach, describe, expect, it, vi } from 'vitest';
import { nextTick, reactive } from 'vue';
import { fireEvent, screen, within } from '@testing-library/vue';
import { renderHost } from '../test/render';
import { ApiError } from '../api';
import type { AuditQuery } from './auditQuery';
const h = reactive({ epoch: 1, phase: 'ready', allowed: true, facts: 'with-space', permissions: 'audit:read' });
vi.mock('../SessionProvider', () => ({ useSession: () => ({ get state() { return { epoch: h.epoch, phase: h.phase }; } }) }));
vi.mock('./access', () => ({ useAdminAccess: () => ({ status: 'ready', get factsKey() { return h.facts; }, get permissionFactsKey() { return h.permissions; }, has: () => h.allowed }) }));
vi.mock('./auditQuery', () => ({ useAuditQuery: () => query }));
import AuditsPage from './AuditsPage.vue';
const eventId = '12345678-1234-1234-1234-123456789012';
const meta = { id: '9223372036854775807', eventId, operationScope: 'UNKNOWN_SCOPE', requestId: 'req', actorUserId: null, targetType: 'user', targetId: '9007199254740993', operation: 'unknown', status: 'SUCCESS', createdAt: '2026-10-20T00:00:00Z', attemptedUsername: null };
const window = { from: '2026-10-17T00:00:00.123456Z', to: '2026-10-24T00:00:00.123456Z' };
let query: AuditQuery;
beforeEach(() => {
  h.epoch++; h.phase = 'ready'; h.allowed = true; h.facts = 'with-space'; h.permissions = 'audit:read';
  query = reactive({ key: 'unit', filters: { limit: 20 }, items: [meta], window, cursor: 'cursor_A', hasMore: true, loading: false, forbidden: false, detail: null, submit: vi.fn(), refresh: vi.fn(), reset: vi.fn(), more: vi.fn(), retry: vi.fn(), open: vi.fn(), close: vi.fn() }) as AuditQuery;
});
const fill = (label: string, value: string) => fireEvent.update(screen.getByLabelText(label), value);
const search = () => fireEvent.submit(screen.getByRole('form', { name: '审计筛选' }));
async function render() {
  const view = renderHost(AuditsPage); await nextTick(); return view;
}
describe('审计页面隔离单元', () => {
  it('编辑草稿不查询，显式提交保持未知代码和组合条件', async () => {
    await render(); await fill('操作域', 'Unknown_%'); await fill('目标类型', 'PARSE_DEAD_LETTER'); await fill('目标 ID', '9223372036854775807'); await fill('请求标识', 'private&request'); await fill('结果', 'FAILED'); await fill('本地登录用户名', 'alice'); expect(query.submit).not.toHaveBeenCalled(); await search();
    expect(query.submit).toHaveBeenCalledWith(expect.objectContaining({ operationScope: 'Unknown_%', targetType: 'PARSE_DEAD_LETTER', targetId: '9223372036854775807', requestId: 'private&request', status: 'FAILED', loginUsername: 'alice' })); expect(query.submit).not.toHaveBeenCalledWith(expect.objectContaining({ from: expect.anything() })); expect(screen.getByText(/本地登录用户名仅匹配本地登录事件/)).toBeTruthy();
  });
  it('无效条件不改写已有结果', async () => {
    await render(); await fill('目标 ID', '3'); await search(); expect(query.submit).not.toHaveBeenCalled(); expect(screen.getByText('输入或请求不符合要求。')).toBeTruthy(); expect(screen.getByText('已加载 1 条')).toBeTruthy();
  });
  it('自定义时间要求双端合法且不超过 31 天', async () => {
    await render(); await fill('时间范围', 'custom'); await fill('起始时间（含）', '2026-10-20T12:00'); await search(); expect(query.submit).not.toHaveBeenCalled(); await fill('截止时间（不含）', '2026-10-21T12:00'); await search(); expect(query.submit).toHaveBeenCalledWith(expect.objectContaining({ from: new Date(2026, 9, 20, 12).toISOString(), to: new Date(2026, 9, 21, 12).toISOString() })); vi.mocked(query.submit).mockClear(); await fill('截止时间（不含）', '2026-12-21T12:00'); await search(); expect(query.submit).not.toHaveBeenCalled();
  });
  it('刷新、重置、每次条数和继续读取使用明确动作', async () => {
    query.filters = { limit: 20, requestId: 'committed' }; await render(); await fill('请求标识', 'draft'); await fireEvent.click(screen.getByRole('button', { name: '刷新列表' })); expect(query.refresh).toHaveBeenCalledTimes(1); await fireEvent.click(screen.getByRole('button', { name: '继续读取' })); expect(query.more).toHaveBeenCalledTimes(1); await fill('每次条数', '50'); expect(query.submit).toHaveBeenCalledWith({ limit: 50, requestId: 'committed' }); await fireEvent.click(screen.getByRole('button', { name: /^重\s*置$/ })); expect(query.reset).toHaveBeenCalledTimes(1); expect((screen.getByLabelText('请求标识') as HTMLInputElement).value).toBe('');
  });
  it('展示实际窗口、大整数、已加载数和原始操作代码', async () => {
    const view = await render(); expect(screen.getByText('UNKNOWN_SCOPE')).toBeTruthy(); expect(screen.getByText('user · 9007199254740993')).toBeTruthy(); expect(view.container.querySelector(`time[datetime="${window.to}"]`)).toBeTruthy(); expect(screen.getByText('已加载 1 条')).toBeTruthy(); expect(screen.queryByText(/共.*条/)).toBeNull(); await fireEvent.click(screen.getByRole('button', { name: `查看事件 ${eventId}` })); expect(query.open).toHaveBeenCalledWith(eventId);
  });
  it('成功空页与首次故障分别展示', async () => {
    query.items = []; query.hasMore = false; await render(); expect(screen.getByText('当前查询没有已记录事件。')).toBeTruthy(); query.window = undefined; query.error = new ApiError(503).message; query.status = 503; await nextTick(); expect(screen.queryByText('当前查询没有已记录事件。')).toBeNull(); expect(screen.getByText('服务暂不可用，请稍后重试。')).toBeTruthy(); await fireEvent.click(screen.getByRole('button', { name: '重试读取' })); expect(query.retry).toHaveBeenCalledTimes(1);
  });
  it('续页失败保留已确认结果，加载期间禁止重复点击', async () => {
    query.error = new ApiError(503).message; query.status = 503; await render(); expect(screen.getByText('已加载 1 条')).toBeTruthy(); expect(screen.queryByRole('button', { name: '继续读取' })).toBeNull(); query.error = undefined; query.loading = true; await nextTick(); expect((screen.getByRole('button', { name: '刷新列表' }) as HTMLButtonElement).disabled).toBe(true);
  });
  it.each([false, true])('区分摘要不可用与空变更：%s', async available => {
    query.detail = { eventId, loading: false, data: { metadata: meta, summaryAvailable: available, changes: [] } }; await render(); const dialog = await screen.findByRole('dialog'); expect(within(dialog).getByText(available ? '无可展示变更。' : /该事件的摘要不可用/)).toBeTruthy(); expect(within(dialog).getByText('9223372036854775807')).toBeTruthy();
  });
  it('详情精确展示 false、零、空字符串、null 和大整数，标记保持文本', async () => {
    query.detail = { eventId, loading: false, data: { metadata: { ...meta, attemptedUsername: '<img src=x onerror=alert(1)>' }, summaryAvailable: true, changes: [{ field: 'flag', before: false, after: null }, { field: 'count', before: 0, after: 9007199254740993n }, { field: '<script>field</script>', before: '', after: 'UNKNOWN_CODE' }] } };
    const view = await render(), dialog = await screen.findByRole('dialog');
    for (const text of ['false', '0', '空值', '空字符串', '9007199254740993', 'UNKNOWN_CODE', '<script>field</script>']) {
      expect(within(dialog).getAllByText(text).length).toBeGreaterThan(0);
    }
    expect(within(dialog).getByText('<img src=x onerror=alert(1)>')).toBeTruthy(); expect(view.container.querySelector('script')).toBeNull(); expect(dialog.querySelector('img')).toBeNull();
  });
  it.each([404, 503])('详情错误只展示安全分类，保持列表：%s', async status => {
    query.detail = { eventId, loading: false, status, error: new ApiError(status).message }; await render(); const dialog = await screen.findByRole('dialog'); expect(within(dialog).getByText(status === 404 ? '该事件不存在或当前不可访问。' : '服务暂不可用，请稍后重试。')).toBeTruthy(); expect(within(dialog).queryByText('内部编号')).toBeNull(); expect(screen.getByText('已加载 1 条')).toBeTruthy();
    if (status === 503) {
      await fireEvent.click(within(dialog).getByRole('button', { name: '重试详情' })); expect(query.open).toHaveBeenCalledWith(eventId);
    }
  });
  it('403 清理草稿并要求显式恢复，不自动请求', async () => {
    await render(); await fill('请求标识', 'sensitive'); query.forbidden = true; query.items = []; query.window = undefined; query.error = new ApiError(403).message; query.status = 403; await nextTick(); expect((screen.getByLabelText('请求标识') as HTMLInputElement).value).toBe(''); expect(screen.queryByText('已加载 1 条')).toBeNull(); expect(query.retry).not.toHaveBeenCalled(); await fireEvent.click(screen.getByRole('button', { name: '重新读取审计' })); expect(query.retry).toHaveBeenCalledTimes(1);
  });
  it.each(['anonymous', 'password', 'loading', 'unavailable'])('身份状态 %s 不显示审计内容', async phase => {
    await render(); await fill('请求标识', 'private'); h.phase = phase; await nextTick(); expect(screen.queryByLabelText('请求标识')).toBeNull(); expect(screen.queryByText('UNKNOWN_SCOPE')).toBeNull();
  });
  it('资格与身份变化清理筛选，恢复默认查询；离开时移除页面', async () => {
    const view = await render(); await fill('请求标识', 'private'); h.allowed = false; await nextTick(); expect(screen.queryByLabelText('请求标识')).toBeNull(); h.allowed = true; h.epoch++; await nextTick(); expect((screen.getByLabelText('请求标识') as HTMLInputElement).value).toBe(''); expect((screen.getByLabelText('时间范围') as HTMLSelectElement).value).toBe('recent'); view.unmount(); expect(screen.queryByText('UNKNOWN_SCOPE')).toBeNull();
  });
  it('页面操作不写地址、存储或网络，不自动轮询', async () => {
    const save = vi.spyOn(Storage.prototype, 'setItem'), href = location.href; vi.useFakeTimers(); const view = await render(); await fill('请求标识', 'secret'); await search(); vi.advanceTimersByTime(60000); await nextTick(); expect(query.refresh).not.toHaveBeenCalled(); expect(save).not.toHaveBeenCalled(); expect(location.href).toBe(href); expect(fetch).not.toHaveBeenCalled(); view.unmount(); vi.useRealTimers();
  });
  it('空间目录变化保留草稿，真正权限事实变化才重新挂载', async () => {
    await render(); await fill('请求标识', 'private-draft');
    for (const facts of ['empty', 'directory-failure', 'with-space']) {
      h.facts = facts; await nextTick(); expect((screen.getByLabelText('请求标识') as HTMLInputElement).value).toBe('private-draft');
    }
    h.permissions = 'audit:read,new'; await nextTick(); expect((screen.getByLabelText('请求标识') as HTMLInputElement).value).toBe(''); expect(query.submit).not.toHaveBeenCalled();
  });
});
