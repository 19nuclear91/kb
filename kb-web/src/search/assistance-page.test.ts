import { beforeEach, expect, it, vi } from 'vitest';
import { h, nextTick, reactive } from 'vue';
import { flushPromises } from '@vue/test-utils';
import { fireEvent, screen, waitFor } from '@testing-library/vue';
import { mountRouter } from '../test/app';
import { deferred, json, me } from '../test/fixtures';
import type { Session } from '../session';
import { assistedApi, AssistanceError, AssistanceProtocolError, type AssistedInput, type AssistedResult } from './assistedApi';
import { assistedResult, assistedResponse } from './assistedFixtures';

const context = reactive({ session: {} as Session, state: { phase: 'ready', me, epoch: 1 } });
vi.mock('../SessionProvider', () => ({ useSession: () => context }));
vi.mock('../favorites/useFavorites', () => ({ useFavorites: () => ({ unavailable: () => false }) }));
vi.mock('../favorites/FavoriteButton.vue', () => ({ default: { render: () => null } }));
vi.mock('../CitationDrawer.vue', () => ({ default: { render: () => null } }));
import SearchPage from '../pages/SearchPage.vue';

beforeEach(() => {
  context.state = { phase: 'ready', me, epoch: 1 };
  context.session = { getSnapshot: () => context.state, json: vi.fn().mockResolvedValue([]), send: vi.fn().mockResolvedValue(json({ items: [], hasMore: false, nextCursor: null })) } as unknown as Session;
  vi.spyOn(assistedApi, 'search').mockImplementation(async (_, input) => sample(input));
});
function sample(input: AssistedInput): AssistedResult {
  const result = assistedResult(input.originalQuery, input.mode), value = result.assistance;
  if (input.mode === 'ORIGINAL') return result;
  value.appliedTerms = [{ start: 0, end: 2, matchedText: 'OA', termId: '1', termVersion: '0', canonicalName: '办公自动化', expressions: ['办公自动化'] }];
  value.suggestions = [
    { kind: 'CORRECTION', start: 3, end: 5, sourceText: '报消', options: [{ termId: '2', termVersion: '0', canonicalName: '报销', description: '费用规则' }] },
    { kind: 'DISAMBIGUATION', start: 6, end: 8, sourceText: 'PM', options: [{ termId: '3', termVersion: '0', canonicalName: '项目管理', description: '项目活动' }, { termId: '4', termVersion: '0', canonicalName: '产品经理', description: '产品职责' }] },
  ];
  for (const chosen of input.selections) {
    const suggestion = value.suggestions.find(item => item.start === chosen.start)!;
    const option = suggestion.options.find(item => item.termId === chosen.termId)!;
    value.appliedTerms.push({ ...option, start: chosen.start, end: chosen.end, matchedText: suggestion.sourceText, expressions: [] });
    value.suggestions = value.suggestions.filter(item => item !== suggestion);
  }
  value.selections = [...input.selections]; value.selectionContext = value.suggestions.length ? `context-${input.selections.length}` : null;
  value.effectiveQuery = input.originalQuery;
  for (const chosen of [...input.selections].sort((a, b) => b.start - a.start)) {
    const term = value.appliedTerms.find(item => item.start === chosen.start)!;
    const points = Array.from(value.effectiveQuery); value.effectiveQuery = points.slice(0, chosen.start).join('') + term.canonicalName + points.slice(chosen.end).join('');
  }
  return result;
}
async function submit() {
  await fireEvent.update(screen.getByLabelText('搜索词'), 'OA 报消 PM');
  await fireEvent.submit(screen.getByLabelText('搜索词').closest('form')!);
  await screen.findByRole('button', { name: '报销' });
}
it('空 hits 仍显示独立公开建议，草稿修改不影响累计采用和原词恢复', async () => {
  const persist = vi.spyOn(Storage.prototype, 'setItem');
  mountRouter(() => h(SearchPage)); await submit();
  expect(screen.getByText('当前条件下未找到可用结果')).toBeTruthy();
  await fireEvent.update(screen.getByLabelText('搜索词'), '未提交草稿'); await fireEvent.update(screen.getByLabelText('文件类型'), 'WORD');
  await fireEvent.click(screen.getByRole('button', { name: '报销' })); await screen.findByRole('button', { name: '项目管理' });
  expect(vi.mocked(assistedApi.search).mock.calls[1][1]).toMatchObject({ originalQuery: 'OA 报消 PM', filters: { fileType: undefined }, selections: [{ kind: 'CORRECTION', start: 3, end: 5, termId: '2' }] });
  await fireEvent.click(screen.getByRole('button', { name: '项目管理' })); await waitFor(() => expect(screen.queryByRole('button', { name: '产品经理' })).toBeNull());
  expect(vi.mocked(assistedApi.search).mock.calls[2][1].selections).toHaveLength(2);
  await fireEvent.click(screen.getByRole('button', { name: '仅搜原词' })); await screen.findByRole('heading', { name: '原词搜索' });
  expect(vi.mocked(assistedApi.search).mock.calls[3][1]).toMatchObject({ originalQuery: 'OA 报消 PM', mode: 'ORIGINAL', selections: [], selectionContext: null });
  await fireEvent.click(screen.getByRole('button', { name: '重新启用辅助' })); await screen.findByRole('button', { name: '报销' });
  expect(vi.mocked(assistedApi.search).mock.calls[4][1].selections).toEqual([]);
  expect(persist).not.toHaveBeenCalled();
});
it('页面非默认上限传入已提交快照，模式切换不改用默认 8000', async () => {
  mountRouter(() => h(SearchPage, { maxQueryCodepoints: 9000 })); await submit();
  await fireEvent.click(screen.getByRole('button', { name: '报销' })); await screen.findByRole('button', { name: '项目管理' });
  await fireEvent.click(screen.getByRole('button', { name: '仅搜原词' })); await screen.findByRole('heading', { name: '原词搜索' });
  await fireEvent.click(screen.getByRole('button', { name: '重新启用辅助' })); await screen.findByRole('button', { name: '报销' });
  expect(vi.mocked(assistedApi.search).mock.calls.map(call => call[1].maxQueryCodepoints)).toEqual([9000, 9000, 9000, 9000]);
});
it('歧义替换与另一处合法扩展混合时，实际解码后仍展示搜索结果', async () => {
  vi.mocked(assistedApi.search).mockRestore();
  vi.mocked(context.session.send).mockImplementation(async (path, init) => {
    if (path !== '/api/search/assisted') return json({ items: [], hasMore: false, nextCursor: null });
    const body = JSON.parse(init!.body as string), adopted = Boolean(body.selections?.length);
    const value = assistedResult('PM 项目');
    value.assistance.appliedTerms = [{ start: 3, end: 5, matchedText: '项目', termId: '1', termVersion: '0', canonicalName: 'PM', expressions: adopted ? ['PM'] : [] }];
    if (adopted) {
      value.assistance.effectiveQuery = '产品 项目';
      value.assistance.selections = [{ kind: 'DISAMBIGUATION', start: 0, end: 2, termId: '2' }];
      value.assistance.appliedTerms.unshift({ start: 0, end: 2, matchedText: 'PM', termId: '2', termVersion: '0', canonicalName: '产品', expressions: [] });
    } else {
      value.assistance.selectionContext = 'opaque';
      value.assistance.suggestions = [{ kind: 'DISAMBIGUATION', start: 0, end: 2, sourceText: 'PM', options: [
        { termId: '1', termVersion: '0', canonicalName: 'PM', description: '项目' },
        { termId: '2', termVersion: '0', canonicalName: '产品', description: '产品' },
      ] }];
    }
    const hits = adopted ? [{ chunkId: '1', docId: '10', versionId: '20', versionNo: '1', spaceId: '3', securityLevel: 1,
      chunkIdx: 0, docTitle: '项目说明', content: '混合扩展后的搜索结果', score: 0.1 }] : [];
    const response = json({ ...value, hits });
    response.headers.set('X-Search-Filter-Version', '1'); response.headers.set('X-Search-Assistance-Version', '1');
    return response;
  });
  mountRouter(() => h(SearchPage));
  await fireEvent.update(screen.getByLabelText('搜索词'), 'PM 项目');
  await fireEvent.submit(screen.getByLabelText('搜索词').closest('form')!);
  await fireEvent.click(await screen.findByRole('button', { name: '产品' }));
  await screen.findByText('混合扩展后的搜索结果');
  expect(screen.getByText('产品 项目')).toBeTruthy();
  expect(screen.queryByText(/无法确认辅助搜索支持/)).toBeNull();
  const searches = vi.mocked(context.session.send).mock.calls.filter(call => call[0] === '/api/search/assisted');
  expect(searches).toHaveLength(2);
  expect(JSON.parse(searches[1][1]!.body as string)).toMatchObject({ query: 'PM 项目', mode: 'ASSISTED', selectionContext: 'opaque',
    selections: [{ kind: 'DISAMBIGUATION', start: 0, end: 2, termId: 2 }] });
});
it.each(['ASSISTED', 'ORIGINAL'] as const)('页面允许的 8001 个补充字符在 %s 完整解码后正常显示，原文不截断', async mode => {
  vi.mocked(assistedApi.search).mockRestore();
  const query = '😀'.repeat(8001);
  vi.mocked(context.session.send).mockImplementation(async path => path === '/api/search/assisted' ? assistedResponse(query, [], mode) : json({ items: [], hasMore: false, nextCursor: null }));
  mountRouter(() => h(SearchPage, { maxQueryCodepoints: 9000 })); await fireEvent.update(screen.getByLabelText('搜索词'), query);
  if (mode === 'ORIGINAL') await fireEvent.click(screen.getByRole('button', { name: '提交原词搜索' }));
  else await fireEvent.submit(screen.getByLabelText('搜索词').closest('form')!);
  await screen.findByText('当前条件下未找到可用结果');
  const request = vi.mocked(context.session.send).mock.calls.find(call => call[0] === '/api/search/assisted')!;
  expect(JSON.parse(request[1]!.body as string)).toMatchObject({ query, mode });
  expect(JSON.parse(request[1]!.body as string)).not.toHaveProperty('maxQueryCodepoints');
  expect(screen.queryByText(/无法确认辅助搜索支持/)).toBeNull();
});
it('建议在途禁止双击，过期后清理凭据并由用户主动重搜', async () => {
  const pending = deferred<AssistedResult>();
  mountRouter(() => h(SearchPage)); await submit();
  vi.mocked(assistedApi.search).mockReturnValueOnce(pending.promise);
  const button = screen.getByRole('button', { name: '报销' }); await fireEvent.click(button); await fireEvent.click(button);
  expect(assistedApi.search).toHaveBeenCalledTimes(2);
  pending.reject(new AssistanceError(409, 'ASSISTANCE_STALE')); await screen.findByText(/建议已过期/);
  expect(screen.queryByRole('button', { name: '报销' })).toBeNull(); expect(assistedApi.search).toHaveBeenCalledTimes(2);
  await fireEvent.click(screen.getByRole('button', { name: '重新搜索' })); await screen.findByRole('button', { name: '报销' });
  expect(vi.mocked(assistedApi.search).mock.calls[2][1].selections).toEqual([]);
});
it.each([new AssistanceError(400, 'ASSISTANCE_LIMIT_EXCEEDED'), new AssistanceError(503, 'ASSISTANCE_UNAVAILABLE'), new AssistanceProtocolError(), new AssistanceError(500)])('失败提供主动原词入口而不自动采用或重发（%#）', async failure => {
  vi.mocked(assistedApi.search).mockRejectedValueOnce(failure);
  mountRouter(() => h(SearchPage)); await fireEvent.update(screen.getByLabelText('搜索词'), 'OA 报消 PM'); await fireEvent.submit(screen.getByLabelText('搜索词').closest('form')!);
  await screen.findByRole('button', { name: '仅搜原词' }); await flushPromises(); expect(assistedApi.search).toHaveBeenCalledOnce();
  await fireEvent.click(screen.getByRole('button', { name: '仅搜原词' })); await screen.findByRole('heading', { name: '原词搜索' });
  expect(vi.mocked(assistedApi.search).mock.calls[1][1].mode).toBe('ORIGINAL');
});
it.each(['hidden', 'identity', 'password', 'unmount'])('辅助状态在 %s 清理，迟到内容不能恢复', async mode => {
  const pending = deferred<AssistedResult>(); vi.mocked(assistedApi.search).mockReturnValueOnce(pending.promise);
  const view = mountRouter(() => h(SearchPage)); await fireEvent.update(screen.getByLabelText('搜索词'), 'OA 报消 PM'); await fireEvent.submit(screen.getByLabelText('搜索词').closest('form')!);
  await waitFor(() => expect(assistedApi.search).toHaveBeenCalledOnce());
  const call = vi.mocked(assistedApi.search).mock.calls[0];
  if (mode === 'hidden') { vi.spyOn(document, 'visibilityState', 'get').mockReturnValue('hidden'); await fireEvent(document, new Event('visibilitychange')); }
  else if (mode === 'unmount') view.unmount();
  else { context.state = { ...context.state, epoch: 2, phase: mode === 'password' ? 'password' : 'ready' }; await nextTick(); }
  expect(call[2]!.aborted).toBe(true); pending.resolve(sample(call[1])); await flushPromises();
  expect(screen.queryByRole('button', { name: '报销' })).toBeNull(); expect(screen.queryByText('OA 报消 PM')).toBeNull();
});
