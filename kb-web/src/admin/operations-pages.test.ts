import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { h as node, nextTick, shallowRef, type VNode } from 'vue';
import { flushPromises } from '@vue/test-utils';
import { fireEvent, screen, waitFor, within } from '@testing-library/vue';
import { mountRouter } from '../test/app';
import { provideAccess } from '../test/access';
import { ApiError, decodeJson } from '../api';
import { deferred, json } from '../test/fixtures';
import { emptyAdminAccess, perm, type AdminAccess } from './access';
import EmbeddingPage from './EmbeddingPage.vue';
import ParseDeadLettersPage from './ParseDeadLettersPage.vue';
import { operationPaths, type EmbeddingRun, type EmbeddingStatus } from './operationsApi';

const h = vi.hoisted(() => ({ epoch: 1, phase: 'ready', read: vi.fn(), send: vi.fn(), check: vi.fn() }));
vi.mock('../SessionProvider', () => ({ useSession: () => ({ get state() { return { epoch: h.epoch, phase: h.phase }; }, session: { json: h.read, send: h.send, getSnapshot: () => ({ epoch: h.epoch, phase: h.phase }) } }) }));
vi.mock('./access', async original => (await import('../test/access')).withFakeAccess(await original()));
const runId = '12345678-1234-1234-1234-123456789012', nextRunId = '22345678-1234-1234-1234-123456789012';
const profile = { profile: { profileId: 'p', provider: 'remote', model: '<script>model</script>', dimension: 1024 }, fingerprint: 'fingerprint', ready: true };
const baseStatus: EmbeddingStatus = { state: 'ACTIVE', activeProfileId: 'p', targetProfileId: null, epoch: 9007199254740993n, runId: null, localEmbedding: { ready: true, reason: 'READY' } };
const baseRun: EmbeddingRun = { runId, targetProfileId: 'p', state: 'BLOCKED', version: 9007199254740993n, totalCount: 9007199254740993n, succeededCount: 9, skippedCount: 1, nextAttemptAt: null, errorCode: 'MODEL_UNAVAILABLE' };
const dead = { id: '9223372036854775807', taskId: '7', versionId: '8', executionGeneration: 9007199254740993n, attempts: 3, failureClass: 'RETRYABLE', failureCode: 'MODEL_UNAVAILABLE', errorSummary: '<script>secret</script>', status: 'OPEN', enteredAt: '2026-10-20T00:00:00Z', resolvedAt: null };
let status: EmbeddingStatus, run: EmbeddingRun, catalog: typeof profile[], items: typeof dead[], total: number | bigint;
beforeEach(() => {
  h.epoch++; h.phase = 'ready'; h.read.mockReset(); h.send.mockReset(); h.check.mockReset(); status = { ...baseStatus }; run = { ...baseRun }; catalog = [profile]; items = [dead]; total = 1;
  Object.defineProperty(document, 'visibilityState', { configurable: true, value: 'visible' });
  h.read.mockImplementation(async (path: string) => {
    if (path === operationPaths.profiles) {
      return catalog;
    }
    if (path === operationPaths.status) {
      return status;
    }
    if (path.startsWith(operationPaths.rebuilds + '/')) {
      return run;
    }
    if (path.startsWith(operationPaths.deadLetters)) {
      const query = new URL(path, 'http://unit.test').searchParams;
      return { page: Number(query.get('page')), size: Number(query.get('size')), total, items };
    }
    throw new Error('unexpected unit request');
  });
  h.send.mockResolvedValue(json({ operationId: runId, runId, profileId: 'p', epoch: 9007199254740994n, version: 0, state: 'QUIESCING', replayed: false }));
});
afterEach(() => { vi.useRealTimers(); Reflect.deleteProperty(document, 'visibilityState'); });
const embeddingCodes = [perm.embeddingRead, perm.embeddingRebuild], deadCodes = [perm.deadLetterRead, perm.deadLetterReplay];
function show(element: VNode, codes: readonly string[], overrides: Partial<AdminAccess> = {}) {
  const value = (list: readonly string[], next: Partial<AdminAccess>) => ({ ...emptyAdminAccess, status: 'ready' as const, factsKey: list.join(','), has: (code: string) => list.includes(code), check: h.check, ...next });
  provideAccess(value(codes, overrides)); const current = shallowRef(element), view = mountRouter(() => current.value);
  return { ...view, async update(next: VNode, list: readonly string[], facts: Partial<AdminAccess> = {}) { provideAccess(value(list, facts)); current.value = next; await nextTick(); } };
}
const fillReason = (value = '依赖已恢复') => fireEvent.update(screen.getByLabelText('操作原因'), value);
const confirm = () => fireEvent.click(screen.getByRole('button', { name: '确认提交' }));
const countReads = (path: string) => h.read.mock.calls.filter(([value]) => String(value).startsWith(path)).length;
const flush = async () => { await flushPromises(); await nextTick(); };
async function click(element: HTMLElement) {
  await flush(); await fireEvent.click(element); await nextTick();
}
async function visibility(value: 'visible' | 'hidden') {
  Object.defineProperty(document, 'visibilityState', { configurable: true, value }); document.dispatchEvent(new Event('visibilitychange')); await nextTick();
}

describe('运维读取资格', () => {
  it.each([['<EmbeddingPage />', EmbeddingPage, [perm.embeddingRebuild]], ['<ParseDeadLettersPage />', ParseDeadLettersPage, [perm.deadLetterReplay]], ['<EmbeddingPage />', EmbeddingPage, ['ROLE_ADMIN']]] as const)('只有写入或角色名不能加载页面 %s', (_, page, codes) => {
    show(node(page), codes); expect(h.read).not.toHaveBeenCalled(); expect(h.send).not.toHaveBeenCalled();
  });
  it('只读资格与空间目录故障独立，不开放操作', async () => {
    const view = show(node(EmbeddingPage), [perm.embeddingRead], { spaceStatus: 'unavailable' }); await screen.findByText('<script>model</script>'); expect(screen.queryByRole('button', { name: '发起重建' })).toBeNull(); expect(view.container.querySelector('script')).toBeNull(); view.unmount();
    show(node(ParseDeadLettersPage), [perm.deadLetterRead]); await screen.findByRole('button', { name: `查看摘要 ${dead.id}` }); expect(screen.queryByRole('button', { name: `重放 ${dead.id}` })).toBeNull(); expect(h.read.mock.calls.every(([path]) => String(path).startsWith('/api/admin/embedding') || String(path).startsWith(operationPaths.deadLetters))).toBe(true);
  });
  it('权限撤销或强制改密立即清空原内容', async () => {
    const view = show(node(EmbeddingPage), embeddingCodes); await screen.findByText('<script>model</script>'); await click(screen.getByRole('button', { name: '发起重建' })); await fillReason('敏感原因'); await view.update(node(EmbeddingPage), []); expect(screen.queryByLabelText('操作原因')).toBeNull(); expect(screen.queryByText('<script>model</script>')).toBeNull(); view.unmount(); h.phase = 'password'; show(node(ParseDeadLettersPage), deadCodes); expect(screen.queryByRole('table')).toBeNull();
  });
});
describe('向量状态与操作', () => {
  it('区分全局与本实例，刷新后 ACTIVE 不补造作业历史', async () => {
    status.localEmbedding = { ready: false, reason: 'MISSING_PROFILE' }; show(node(EmbeddingPage), embeddingCodes); await screen.findByText('未就绪或不可确认'); expect(screen.getByText('已激活')).toBeTruthy(); expect(screen.queryByText('作业标识')).toBeNull(); expect(countReads(operationPaths.rebuilds)).toBe(0);
  });
  it('零目标与 VERIFYING 不按计数宣告完成', async () => {
    status = { ...status, state: 'VERIFYING', targetProfileId: 'p', runId }; run = { ...run, state: 'VERIFYING', totalCount: 0, succeededCount: 0, skippedCount: 0, errorCode: null }; show(node(EmbeddingPage), embeddingCodes); await screen.findByText('重算计数完成后仍需后台校验，校验通过才恢复向量召回。'); expect(screen.queryByText('重建已完成，目标模型已激活。')).toBeNull(); expect((screen.getByRole('button', { name: '发起重建' }) as HTMLButtonElement).disabled).toBe(true);
  });
  it('成功的已跟踪作业与 ACTIVE 对齐后才确认完成', async () => {
    status = { ...status, state: 'REBUILDING', targetProfileId: 'p', runId }; run = { ...run, state: 'REBUILDING' }; show(node(EmbeddingPage), embeddingCodes); await screen.findByText(runId); status = { ...baseStatus }; run = { ...run, state: 'SUCCEEDED', errorCode: null }; await click(screen.getByRole('button', { name: '重新读取' })); await screen.findByText('重建已完成，目标模型已激活。');
  });
  it('未就绪目标、配置读取失败和未知阶段禁止新提交', async () => {
    catalog = [{ ...profile, ready: false }]; const view = show(node(EmbeddingPage), embeddingCodes); await screen.findByText('<script>model</script>'); await click(screen.getByRole('button', { name: '发起重建' })); expect((screen.getByRole('button', { name: '确认提交' }) as HTMLButtonElement).disabled).toBe(true); view.unmount();
    h.read.mockRejectedValue(new ApiError(503)); const errorView = show(node(EmbeddingPage), embeddingCodes); await screen.findAllByText('服务暂不可用，请稍后重试。'); expect(h.send).not.toHaveBeenCalled(); errorView.unmount(); h.read.mockImplementation(async (path: string) => path === operationPaths.profiles ? [profile] : { ...baseStatus, state: 'NEW_STATE' }); show(node(EmbeddingPage), embeddingCodes); await screen.findByText('未知状态'); expect((screen.getByRole('button', { name: '发起重建' }) as HTMLButtonElement).disabled).toBe(true);
  });
  it('发起双击只发送一次，并精确绑定原 epoch 与同一 profile', async () => {
    const pending = deferred<Response>(); h.send.mockReturnValue(pending.promise); show(node(EmbeddingPage), embeddingCodes); await screen.findByText('<script>model</script>'); await click(screen.getByRole('button', { name: '发起重建' })); await fillReason();
    void confirm(); void confirm(); await waitFor(() => expect(h.send).toHaveBeenCalledTimes(1)); expect(decodeJson(h.send.mock.calls[0][1].body)).toEqual({ profileId: 'p', expectedEpoch: 9007199254740993n, reason: '依赖已恢复' }); pending.resolve(json({ operationId: runId, runId, profileId: 'p', epoch: 9007199254740994n, version: 0, state: 'QUIESCING', replayed: false })); await flush(); await screen.findByText('请求已受理，作业将在后台执行。');
  });
  it('恢复只绑定当前 BLOCKED 作业版本，刷新不改写不确定提交', async () => {
    status = { ...status, state: 'BLOCKED', targetProfileId: 'p', runId }; h.send.mockRejectedValue(new TypeError('offline')); show(node(EmbeddingPage), embeddingCodes); await screen.findAllByText('9007199254740993'); await click(screen.getByRole('button', { name: '恢复阻塞作业' })); await fillReason(); await confirm(); await screen.findByRole('button', { name: '按原内容重试' });
    const first = h.send.mock.calls[0][1]; run = { ...run, version: 9007199254740994n }; await click(screen.getByRole('button', { name: '重新读取核对' })); await waitFor(() => expect(countReads(operationPaths.status)).toBe(2)); await click(screen.getByRole('button', { name: '按原内容重试' })); await waitFor(() => expect(h.send).toHaveBeenCalledTimes(2)); expect(h.send.mock.calls[1][1].body).toBe(first.body); expect(h.send.mock.calls[1][1].headers['X-Request-Id']).toBe(first.headers['X-Request-Id']); expect(decodeJson(first.body)).toEqual({ expectedVersion: 9007199254740993n, reason: '依赖已恢复' });
  });
  it('409 重读事实，不自动补发；503 保留原内容待核对', async () => {
    h.send.mockResolvedValue(new Response(null, { status: 409 })); show(node(EmbeddingPage), embeddingCodes); await screen.findByText('<script>model</script>'); await click(screen.getByRole('button', { name: '发起重建' })); await fillReason(); await confirm(); await screen.findByText('状态已变化，请刷新后确认。'); await waitFor(() => expect(countReads(operationPaths.status)).toBe(2)); expect(h.send).toHaveBeenCalledTimes(1); h.send.mockResolvedValue(new Response(null, { status: 503 })); await flush(); await confirm(); await screen.findByRole('button', { name: '按原内容重试' }); expect((screen.getByLabelText('操作原因') as HTMLTextAreaElement).disabled).toBe(true);
  });
  it('隐藏、稳定状态及故障停止轮询，恢复先核对权限', async () => {
    vi.useFakeTimers(); status = { ...status, state: 'REBUILDING', targetProfileId: 'p', runId }; run = { ...run, state: 'REBUILDING' }; show(node(EmbeddingPage), embeddingCodes); await flush(); expect(countReads(operationPaths.status)).toBe(1); await vi.advanceTimersByTimeAsync(4999); await nextTick(); expect(countReads(operationPaths.status)).toBe(1); await vi.advanceTimersByTimeAsync(1); await nextTick(); expect(countReads(operationPaths.status)).toBe(2);
    await visibility('hidden'); await vi.advanceTimersByTimeAsync(20000); await nextTick(); expect(countReads(operationPaths.status)).toBe(2); status = { ...status, state: 'BLOCKED' }; run = { ...run, state: 'BLOCKED' }; await visibility('visible'); await flush(); expect(h.check).toHaveBeenCalledWith(true); await vi.advanceTimersByTimeAsync(20000); await nextTick(); expect(countReads(operationPaths.status)).toBe(3);
  });
  it('未完成的一轮不重叠，卸载后忽略迟到的旧 run', async () => {
    vi.useFakeTimers(); status = { ...status, state: 'REBUILDING', targetProfileId: 'p', runId }; const pending = deferred<EmbeddingRun>(); h.read.mockImplementation(async (path: string) => path === operationPaths.profiles ? catalog : path === operationPaths.status ? status : pending.promise); const view = show(node(EmbeddingPage), embeddingCodes); await flush(); await vi.advanceTimersByTimeAsync(30000); await nextTick(); expect(countReads(operationPaths.status)).toBe(1); view.unmount(); pending.resolve(run); await flush(); expect(screen.queryByText(runId)).toBeNull();
  });
  it('读取故障后不轮询，显式重新读取才恢复', async () => {
    vi.useFakeTimers(); status = { ...status, state: 'REBUILDING', targetProfileId: 'p', runId }; run = { ...run, state: 'REBUILDING' }; show(node(EmbeddingPage), embeddingCodes); await flush(); h.read.mockRejectedValue(new ApiError(503)); await vi.advanceTimersByTimeAsync(5000); await nextTick(); const calls = h.read.mock.calls.length; await vi.advanceTimersByTimeAsync(20000); await nextTick(); expect(h.read.mock.calls.length).toBe(calls); expect(screen.getAllByText('服务暂不可用，请稍后重试。').length).toBeGreaterThan(0);
  });
  it('隐藏期间全局换作业，旧 run 迟到不能覆盖新目标', async () => {
    status = { ...status, state: 'REBUILDING', targetProfileId: 'p', runId }; const old = deferred<EmbeddingRun>(); h.read.mockImplementation(async (path: string) => path === operationPaths.profiles ? catalog : path === operationPaths.status ? status : path.endsWith(runId) ? old.promise : { ...run, runId: nextRunId, state: 'REBUILDING' }); show(node(EmbeddingPage), embeddingCodes); await waitFor(() => expect(countReads(operationPaths.run(runId))).toBe(1)); await visibility('hidden'); await flush(); status = { ...status, runId: nextRunId }; await visibility('visible'); await screen.findByText(nextRunId); old.resolve({ ...run, state: 'REBUILDING' }); await flush(); expect(screen.queryByText(runId)).toBeNull(); expect(screen.getByText(nextRunId)).toBeTruthy();
  });
});
describe('死信列表与重放', () => {
  it('默认 OPEN、精确计数，停留不轮询；摘要只展示文本', async () => {
    total = 9007199254740993n; const view = show(node(ParseDeadLettersPage), deadCodes); await screen.findByText('第 1 页 · 共 9007199254740993 条'); expect(h.read.mock.calls[0][0]).toContain('status=OPEN'); await flush(); await click(screen.getByRole('button', { name: `查看摘要 ${dead.id}` })); await screen.findByText('<script>secret</script>'); expect(view.container.querySelector('script')).toBeNull(); expect(screen.getByText('未处置')).toBeTruthy(); expect(h.read).toHaveBeenCalledTimes(1); vi.useFakeTimers(); await vi.advanceTimersByTimeAsync(60000); await nextTick(); expect(h.read).toHaveBeenCalledTimes(1);
  });
  it('翻页后重新筛选回第一页，每次实际查询使用新标识', async () => {
    total = 100; show(node(ParseDeadLettersPage), deadCodes); await screen.findByRole('button', { name: '下一页' }); await click(screen.getByRole('button', { name: '下一页' })); await screen.findByText('第 2 页 · 共 100 条'); await click(screen.getByRole('button', { name: /筛\s*选/ })); await screen.findByText('第 1 页 · 共 100 条'); const ids = h.read.mock.calls.map(([, init]) => init.headers['X-Request-Id']); expect(new Set(ids).size).toBe(ids.length);
  });
  it('非法时间不发送查询，读取故障不伪装空列表', async () => {
    show(node(ParseDeadLettersPage), deadCodes); await screen.findByRole('button', { name: `重放 ${dead.id}` }); await fireEvent.update(screen.getByLabelText('起始时间（含）'), '2026-10-23T08:00'); await fireEvent.update(screen.getByLabelText('截止时间（不含）'), '2026-10-22T08:00'); await click(screen.getByRole('button', { name: /筛\s*选/ })); expect(h.read).toHaveBeenCalledTimes(1); expect(screen.getByText('输入或请求不符合要求。')).toBeTruthy(); h.read.mockRejectedValue(new ApiError(503)); await click(screen.getByRole('button', { name: '刷新列表' })); await screen.findByText('服务暂不可用，请稍后重试。'); expect(screen.queryByText('第 1 页 · 共 0 条')).toBeNull();
  });
  it('重放成功只表示入队，刷新后保留正确筛选', async () => {
    h.send.mockImplementation(async () => { items = []; total = 0; return json({ ...dead, status: 'REPLAYED' }); }); show(node(ParseDeadLettersPage), deadCodes); await click(await screen.findByRole('button', { name: `重放 ${dead.id}` })); await fillReason('😀'.repeat(300)); await confirm(); await screen.findByText(`死信 ${dead.id} 已重放，新执行周期已重新入队；解析结果需后续核对。`); expect(h.send.mock.calls[0][0]).toContain(dead.id); expect(decodeJson(h.send.mock.calls[0][1].body)).toEqual({ reason: '😀'.repeat(300) }); await screen.findByText('第 1 页 · 共 0 条'); expect(h.read.mock.calls.at(-1)![0]).toContain('status=OPEN');
  });
  it('响应丢失后条目消失不能确认成功；匹配重试保留原标识', async () => {
    h.send.mockRejectedValue(new TypeError('offline')); show(node(ParseDeadLettersPage), deadCodes); await click(await screen.findByRole('button', { name: `重放 ${dead.id}` })); await fillReason(); await confirm(); await screen.findByRole('button', { name: '按原内容重试' }); const first = h.send.mock.calls[0][1]; items = []; total = 0; await click(screen.getByRole('button', { name: '重新读取核对' })); await screen.findByText('第 1 页 · 共 0 条'); expect(screen.queryByText(/已重放，新执行周期/)).toBeNull(); h.send.mockResolvedValue(json({ ...dead, status: 'REPLAYED' })); await click(screen.getByRole('button', { name: '按原内容重试' })); await screen.findByText(`死信 ${dead.id} 已重放，新执行周期已重新入队；解析结果需后续核对。`); expect(h.send.mock.calls[1][1].body).toBe(first.body); expect(h.send.mock.calls[1][1].headers['X-Request-Id']).toBe(first.headers['X-Request-Id']);
  });
  it.each([404, 409])('%s 重读列表且不自动重传', async code => {
    h.send.mockResolvedValue(new Response(null, { status: code })); show(node(ParseDeadLettersPage), deadCodes); await click(await screen.findByRole('button', { name: `重放 ${dead.id}` })); await fillReason(); await confirm(); await waitFor(() => expect(h.read).toHaveBeenCalledTimes(2)); expect(h.send).toHaveBeenCalledTimes(1); expect(screen.queryByText(/已重放，新执行周期/)).toBeNull();
  });
  it('超长原因不提交；放弃核对后再次提交生成新标识', async () => {
    h.send.mockRejectedValue(new TypeError('offline')); show(node(ParseDeadLettersPage), deadCodes); await click(await screen.findByRole('button', { name: `重放 ${dead.id}` })); await fillReason('x'.repeat(2001)); await confirm(); expect(h.send).not.toHaveBeenCalled(); await fillReason(); await confirm(); await screen.findByRole('button', { name: '放弃核对' }); const first = h.send.mock.calls[0][1].headers['X-Request-Id']; await click(screen.getByRole('button', { name: '放弃核对' })); await confirm(); await waitFor(() => expect(h.send).toHaveBeenCalledTimes(2)); expect(h.send.mock.calls[1][1].headers['X-Request-Id']).not.toBe(first);
  });
  it('错目标回执保留待核对；关闭后查看另一目标不串写', async () => {
    h.send.mockResolvedValue(json({ ...dead, id: '99', status: 'REPLAYED' })); items = [dead, { ...dead, id: '9' }]; show(node(ParseDeadLettersPage), deadCodes); await click(await screen.findByRole('button', { name: `重放 ${dead.id}` })); await fillReason(); await confirm(); await screen.findByRole('button', { name: '按原内容重试' }); await click(within(screen.getByRole('dialog')).getByRole('button', { name: /^关\s*闭$/ })); await click(screen.getByRole('button', { name: '重放 9' })); expect(screen.getByRole('dialog').textContent).toContain('死信 9'); expect((screen.getByLabelText('操作原因') as HTMLTextAreaElement).value).toBe('');
  });
  it('权限撤销、身份切换及 403 不恢复旧内容', async () => {
    h.send.mockResolvedValue(new Response(null, { status: 403 })); const view = show(node(ParseDeadLettersPage), deadCodes); await click(await screen.findByRole('button', { name: `重放 ${dead.id}` })); await fillReason(); await confirm(); await waitFor(() => expect(h.check).toHaveBeenCalledWith(true)); await view.update(node(ParseDeadLettersPage), []); expect(screen.queryByRole('dialog')).toBeNull(); expect(screen.queryByRole('table')).toBeNull(); h.epoch++; h.phase = 'anonymous'; await view.update(node(ParseDeadLettersPage), deadCodes); expect(screen.queryByText(dead.id)).toBeNull();
  });
  it('重放后空页只进行一次有界回退', async () => {
    total = 21; show(node(ParseDeadLettersPage), deadCodes); await screen.findByRole('button', { name: '下一页' }); await click(screen.getByRole('button', { name: '下一页' })); await screen.findByText('第 2 页 · 共 21 条'); await flush(); h.send.mockImplementation(async () => { total = 0; items = []; return json({ ...dead, status: 'REPLAYED' }); }); await click(screen.getByRole('button', { name: `重放 ${dead.id}` })); await fillReason(); await confirm(); await screen.findByText('第 1 页 · 共 0 条'); expect(h.read).toHaveBeenCalledTimes(4);
  });
  it('身份切换后旧写回执不能关闭新目标的确认表单', async () => {
    const old = deferred<Response>(); h.send.mockReturnValue(old.promise); items = [dead, { ...dead, id: '9' }]; const view = show(node(ParseDeadLettersPage), deadCodes); await click(await screen.findByRole('button', { name: `重放 ${dead.id}` })); await fillReason(); await confirm(); await waitFor(() => expect(h.send).toHaveBeenCalledTimes(1)); h.epoch++; await view.update(node(ParseDeadLettersPage), deadCodes); await click(await screen.findByRole('button', { name: '重放 9' })); await fillReason('新目标原因'); old.resolve(json({ ...dead, status: 'REPLAYED' })); await flush(); expect(screen.getByRole('dialog').textContent).toContain('死信 9'); expect((screen.getByLabelText('操作原因') as HTMLTextAreaElement).value).toBe('新目标原因'); expect(screen.queryByText(/已重放，新执行周期/)).toBeNull();
  });
});
