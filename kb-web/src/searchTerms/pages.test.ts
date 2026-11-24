import { beforeEach, expect, it, vi } from 'vitest';
import { h, reactive } from 'vue';
import { fireEvent, screen, waitFor, within } from '@testing-library/vue';
import { flushPromises } from '@vue/test-utils';
import { mountRouter } from '../test/app';
import { deferred, json, me } from '../test/fixtures';
import type { Session } from '../session';
import { ApiError } from '../api';
import { termApi, TermError, type TermPage, type Term, type TermReceipt } from './api';
import { sampleTerm, sampleCorrection, termPage } from './fixtures';

const context = reactive({ session: {} as Session, state: { phase: 'ready', epoch: 1, me }, codes: ['search:term:read', 'search:term:write'], facts: '1', checking: false });
const check = vi.hoisted(() => vi.fn());
vi.mock('../SessionProvider', () => ({ useSession: () => context }));
vi.mock('../admin/access', async original => ({ ...await original<typeof import('../admin/access')>(), useAdminAccess: () => ({ has: (code: string) => context.codes.includes(code), get factsKey() { return context.facts; }, get checking() { return context.checking; }, check }) }));
import SearchTermsPage from '../admin/SearchTermsPage.vue';
const mount = () => mountRouter(() => h(SearchTermsPage), { path: '/admin/search-terms' });
beforeEach(() => {
  context.state = { phase: 'ready', epoch: 1, me }; context.codes = ['search:term:read', 'search:term:write']; context.facts = '1'; context.checking = false; check.mockReset();
  context.session = { getSnapshot: () => context.state, refresh: vi.fn().mockResolvedValue(undefined), send: vi.fn().mockResolvedValue(json({ kind: 'TERM', id: sampleTerm.termId, version: '1', dictionaryRevision: '2', replayed: false })) } as unknown as Session;
  vi.spyOn(termApi, 'query').mockImplementation(async (_, kind) => kind === 'TERM' ? termPage([sampleTerm]) : termPage([sampleCorrection]));
  vi.spyOn(termApi, 'detail').mockImplementation(async (_, kind) => kind === 'TERM' ? sampleTerm : sampleCorrection);
  vi.spyOn(termApi, 'meanings').mockResolvedValue(termPage([{ termId: sampleTerm.termId, canonicalName: sampleTerm.canonicalName, description: sampleTerm.description, version: sampleTerm.version }]));
});
it('只读目录开放但写动作禁用，只写资格不读取任何目录', async () => {
  context.codes = ['search:term:read']; const view = mount(); await screen.findByText('公开释义');
  expect((screen.getByRole('button', { name: '新建术语组' }) as HTMLButtonElement).disabled).toBe(true); view.unmount();
  vi.mocked(termApi.query).mockClear(); context.codes = ['search:term:write']; mount(); await screen.findByText('当前没有词典读取资格'); expect(termApi.query).not.toHaveBeenCalled();
});
it('草稿不改变续页条件，修订冲突废弃旧页，视图迟到不污染新目录', async () => {
  vi.mocked(termApi.query).mockResolvedValueOnce(termPage([sampleTerm], '1', 'cursor-1'));
  mount(); await screen.findByText('公开释义'); await fireEvent.update(screen.getByLabelText('词典名称筛选'), '草稿'); await fireEvent.update(screen.getByLabelText('词典状态筛选'), 'false');
  vi.mocked(termApi.query).mockRejectedValueOnce(new TermError(409, 'DICTIONARY_CHANGED'));
  await fireEvent.click(screen.getByRole('button', { name: '继续读取词典' })); await screen.findByText(/词典已变化/);
  expect(vi.mocked(termApi.query).mock.calls[1][2]).toEqual({ nameQuery: '', limit: 20 }); expect(screen.queryByText('公开释义')).toBeNull();
  const late = deferred<TermPage<Term>>(); vi.mocked(termApi.query).mockReturnValueOnce(late.promise);
  await fireEvent.click(screen.getByRole('button', { name: '重新读取词典' })); await fireEvent.click(screen.getByRole('button', { name: '错词映射' }));
  await screen.findByText(/当前不可用于建议/); late.resolve(termPage([{ ...sampleTerm, description: '旧视图迟到' }])); await flushPromises(); expect(screen.queryByText('旧视图迟到')).toBeNull();
});
it('共享含义独立分页且通过稳定目标和表达读取', async () => {
  vi.mocked(termApi.meanings).mockResolvedValueOnce(termPage([{ termId: sampleTerm.termId, canonicalName: '项目含义', description: '第一含义', version: '0' }], '1', 'meanings-cursor'));
  mount(); await screen.findByText('公开释义'); await fireEvent.click(screen.getByRole('button', { name: '查看共享含义' })); await screen.findByText('第一含义');
  await fireEvent.click(screen.getByRole('button', { name: '继续读取含义' })); await waitFor(() => expect(termApi.meanings).toHaveBeenCalledTimes(2));
  expect(vi.mocked(termApi.meanings).mock.calls[1].slice(1, 4)).toEqual([sampleTerm.termId, 'PM', 'meanings-cursor']);
});
it('共享别名可保存，重复提交互斥，成功回执与随后刷新失败分别显示', async () => {
  const pending = deferred<Response>(); vi.mocked(context.session.send).mockReturnValueOnce(pending.promise);
  mount(); await screen.findByText('公开释义'); await fireEvent.click(screen.getByRole('button', { name: `编辑术语 ${sampleTerm.termId}` }));
  const dialog = await screen.findByRole('dialog', { name: '编辑术语组' }); await fireEvent.update(within(dialog).getByLabelText('简短释义'), '更新释义');
  await fireEvent.click(within(dialog).getByRole('button', { name: '确认提交' })); await fireEvent.click(within(dialog).getByRole('button', { name: '确认提交' })); expect(context.session.send).toHaveBeenCalledOnce();
  expect(vi.mocked(context.session.send).mock.calls[0][1]!.body).toContain('"text":"PM"');
  vi.mocked(termApi.query).mockRejectedValueOnce(new TermError(503, 'UNAVAILABLE'));
  pending.resolve(json({ kind: 'TERM', id: sampleTerm.termId, version: '1', dictionaryRevision: '2', replayed: false }));
  await screen.findByText(/已确认词典维护/); await screen.findByText(/词典服务暂不可用/);
});
it('未知提交只按原意图重试或查本人回执，404 和同值重读不结清未知结果', async () => {
  vi.mocked(context.session.send).mockRejectedValueOnce(new ApiError(0));
  vi.spyOn(termApi, 'receipt').mockRejectedValueOnce(new TermError(404, 'NOT_FOUND'));
  mount(); await screen.findByText('公开释义'); await fireEvent.click(screen.getByRole('button', { name: `编辑术语 ${sampleTerm.termId}` }));
  let dialog = await screen.findByRole('dialog', { name: '编辑术语组' }); await fireEvent.update(within(dialog).getByLabelText('简短释义'), '更新释义');
  await fireEvent.click(within(dialog).getByRole('button', { name: '确认提交' })); await screen.findByText(/提交结果尚未确认/);
  const original = vi.mocked(context.session.send).mock.calls[0][1]!;
  await fireEvent.click(screen.getByRole('button', { name: '重新读取词典' })); await screen.findByText('公开释义'); expect(screen.queryByText(/已确认词典维护/)).toBeNull();
  await fireEvent.click(within(dialog).getByRole('button', { name: '重新读取核对' })); await screen.findByText(/未取得本人回执/); expect(screen.queryByText(/已确认词典维护/)).toBeNull();
  await fireEvent.click(within(dialog).getByRole('button', { name: '按原内容重试' })); await screen.findByText(/已确认词典维护/);
  const retry = vi.mocked(context.session.send).mock.calls[1][1]!; expect(retry.body).toBe(original.body); expect(retry.headers).toEqual(original.headers);
});
it.each(['NAME_CONFLICT', 'EXPRESSION_CONFLICT', 'VERSION_CONFLICT'] as const)('冲突 %s 保留草稿且不自动覆盖或重发，重读后才能新确认', async reason => {
  vi.mocked(context.session.send).mockResolvedValueOnce(new Response(JSON.stringify({ code: 500, data: null, reasonCode: reason, message: 'PRIVATE' }), { status: 409, headers: { 'Content-Type': 'application/json' } }));
  mount(); await screen.findByText('公开释义'); await fireEvent.click(screen.getByRole('button', { name: `编辑术语 ${sampleTerm.termId}` }));
  const dialog = await screen.findByRole('dialog', { name: '编辑术语组' }); await fireEvent.update(within(dialog).getByLabelText('简短释义'), '保留草稿');
  await fireEvent.click(within(dialog).getByRole('button', { name: '确认提交' })); await screen.findByRole('button', { name: '重新读取当前词条' });
  expect((within(dialog).getByLabelText('简短释义') as HTMLTextAreaElement).value).toBe('保留草稿'); expect(context.session.send).toHaveBeenCalledOnce();
  expect(screen.queryByText('PRIVATE')).toBeNull();
});
it('未知结果本人回执确认保留首次版本，不用当前读取替代', async () => {
  vi.mocked(context.session.send).mockRejectedValueOnce(new ApiError(0));
  vi.spyOn(termApi, 'receipt').mockResolvedValueOnce({ kind: 'TERM', id: sampleTerm.termId, version: '1', dictionaryRevision: '2', replayed: true });
  mount(); await screen.findByText('公开释义'); await fireEvent.click(screen.getByRole('button', { name: `编辑术语 ${sampleTerm.termId}` }));
  const dialog = await screen.findByRole('dialog', { name: '编辑术语组' }); await fireEvent.update(within(dialog).getByLabelText('简短释义'), '更新释义');
  await fireEvent.click(within(dialog).getByRole('button', { name: '确认提交' })); await screen.findByText(/提交结果尚未确认/);
  await fireEvent.click(within(dialog).getByRole('button', { name: '重新读取核对' })); await screen.findByText(/首次版本 1 · 修订 2（匹配重放）/);
  expect(context.session.send).toHaveBeenCalledOnce(); expect(termApi.receipt).toHaveBeenCalledOnce();
});
it('未知结果关闭再打开仍按原标识与载荷重试，不以重读同值结清', async () => {
  vi.mocked(context.session.send).mockRejectedValueOnce(new ApiError(0));
  mount(); await screen.findByText('公开释义'); await fireEvent.click(screen.getByRole('button', { name: `编辑术语 ${sampleTerm.termId}` }));
  let dialog = await screen.findByRole('dialog', { name: '编辑术语组' }); await fireEvent.update(within(dialog).getByLabelText('简短释义'), '关闭前原意图');
  await fireEvent.click(within(dialog).getByRole('button', { name: '确认提交' })); await screen.findByText(/提交结果尚未确认/);
  const original = vi.mocked(context.session.send).mock.calls[0][1]!;
  await fireEvent.click(within(dialog).getByRole('button', { name: '关闭此对话框' }));
  await fireEvent.click(screen.getByRole('button', { name: `编辑术语 ${sampleTerm.termId}` }));
  dialog = await screen.findByRole('dialog', { name: '编辑术语组' }); await screen.findByText(/提交结果尚未确认/);
  expect((within(dialog).getByRole('button', { name: '确认提交' }) as HTMLButtonElement).disabled).toBe(true);
  expect(context.session.send).toHaveBeenCalledOnce(); expect(screen.queryByText(/已确认词典维护/)).toBeNull();
  await fireEvent.click(within(dialog).getByRole('button', { name: '按原内容重试' })); await screen.findByText(/已确认词典维护/);
  const retry = vi.mocked(context.session.send).mock.calls[1][1]!; expect(retry.body).toBe(original.body); expect(retry.headers).toEqual(original.headers);
});
it.each(['success', 'failure'])('放弃 A 后产生未知 B，A 的迟到回执 %s 不清理或覆盖 B', async result => {
  const old = deferred<TermReceipt>(); vi.spyOn(termApi, 'receipt').mockReturnValueOnce(old.promise);
  vi.mocked(context.session.send).mockRejectedValueOnce(new ApiError(0)).mockRejectedValueOnce(new ApiError(0));
  mount(); await screen.findByText('公开释义'); await fireEvent.click(screen.getByRole('button', { name: `编辑术语 ${sampleTerm.termId}` }));
  let dialog = await screen.findByRole('dialog', { name: '编辑术语组' }); await fireEvent.update(within(dialog).getByLabelText('简短释义'), '意图 A');
  await fireEvent.click(within(dialog).getByRole('button', { name: '确认提交' })); await screen.findByText(/提交结果尚未确认/);
  await fireEvent.click(within(dialog).getByRole('button', { name: '重新读取核对' })); await screen.findByText('正在核对本人回执…');
  const requestA = vi.mocked(termApi.receipt).mock.calls[0];
  await fireEvent.click(within(dialog).getByRole('button', { name: '放弃核对' }));
  await fireEvent.update(within(dialog).getByLabelText('简短释义'), '意图 B');
  await fireEvent.click(within(dialog).getByRole('button', { name: '确认提交' })); await screen.findByText(/提交结果尚未确认/);
  const requestB = vi.mocked(context.session.send).mock.calls[1][1]!;
  expect(requestB.headers).not.toEqual(vi.mocked(context.session.send).mock.calls[0][1]!.headers);
  expect(requestA[2]!.aborted).toBe(true);
  if (result === 'success') old.resolve({ kind: 'TERM', id: sampleTerm.termId, version: '1', dictionaryRevision: '2', replayed: false });
  else old.reject(new TermError(404, 'NOT_FOUND'));
  await flushPromises(); expect(screen.queryByText(/已确认词典维护|未取得本人回执|正在核对本人回执/)).toBeNull();
  expect((within(dialog).getByRole('button', { name: '确认提交' }) as HTMLButtonElement).disabled).toBe(true);
  await fireEvent.click(within(dialog).getByRole('button', { name: '关闭此对话框' }));
  await fireEvent.click(screen.getByRole('button', { name: `编辑术语 ${sampleTerm.termId}` })); dialog = await screen.findByRole('dialog', { name: '编辑术语组' });
  await screen.findByText(/提交结果尚未确认/); await fireEvent.click(within(dialog).getByRole('button', { name: '按原内容重试' })); await screen.findByText(/已确认词典维护/);
  const retry = vi.mocked(context.session.send).mock.calls[2][1]!; expect(retry.body).toBe(requestB.body); expect(retry.headers).toEqual(requestB.headers);
});
it('回执等待中主动重试原意图，旧核对代次不能结清重试后的未知结果', async () => {
  const old = deferred<TermReceipt>(), retry = deferred<Response>(); vi.spyOn(termApi, 'receipt').mockReturnValueOnce(old.promise);
  vi.mocked(context.session.send).mockRejectedValueOnce(new ApiError(0)).mockReturnValueOnce(retry.promise);
  mount(); await screen.findByText('公开释义'); await fireEvent.click(screen.getByRole('button', { name: `编辑术语 ${sampleTerm.termId}` }));
  const dialog = await screen.findByRole('dialog', { name: '编辑术语组' });
  await fireEvent.click(within(dialog).getByRole('button', { name: '确认提交' })); await screen.findByText(/提交结果尚未确认/);
  await fireEvent.click(within(dialog).getByRole('button', { name: '重新读取核对' })); await screen.findByText('正在核对本人回执…');
  await fireEvent.click(within(dialog).getByRole('button', { name: '按原内容重试' }));
  expect(vi.mocked(termApi.receipt).mock.calls[0][2]!.aborted).toBe(true);
  retry.reject(new ApiError(0)); await screen.findByText(/提交结果尚未确认/);
  old.resolve({ kind: 'TERM', id: sampleTerm.termId, version: '1', dictionaryRevision: '2', replayed: false }); await flushPromises();
  expect(screen.queryByText(/已确认词典维护/)).toBeNull(); expect(screen.getByRole('dialog')).toBeTruthy();
  expect((within(dialog).getByRole('button', { name: '确认提交' }) as HTMLButtonElement).disabled).toBe(true);
});
it('同意图连续核对只接受最后一代回执，旧 404 不覆盖新成功', async () => {
  const old = deferred<TermReceipt>(), latest = deferred<TermReceipt>(); vi.spyOn(termApi, 'receipt').mockReturnValueOnce(old.promise).mockReturnValueOnce(latest.promise);
  vi.mocked(context.session.send).mockRejectedValueOnce(new ApiError(0));
  mount(); await screen.findByText('公开释义'); await fireEvent.click(screen.getByRole('button', { name: `编辑术语 ${sampleTerm.termId}` })); const dialog = await screen.findByRole('dialog', { name: '编辑术语组' });
  await fireEvent.click(within(dialog).getByRole('button', { name: '确认提交' })); await screen.findByText(/提交结果尚未确认/);
  await fireEvent.click(within(dialog).getByRole('button', { name: '重新读取核对' })); await fireEvent.click(within(dialog).getByRole('button', { name: '重新读取核对' }));
  expect(vi.mocked(termApi.receipt).mock.calls[0][2]!.aborted).toBe(true);
  expect(vi.mocked(termApi.receipt).mock.calls[0][1]).toBe(vi.mocked(termApi.receipt).mock.calls[1][1]);
  old.reject(new TermError(404, 'NOT_FOUND')); await flushPromises(); expect(screen.queryByText(/未取得本人回执/)).toBeNull();
  latest.resolve({ kind: 'TERM', id: sampleTerm.termId, version: '1', dictionaryRevision: '2', replayed: false }); await screen.findByText(/已确认词典维护/);
  expect(screen.queryByRole('dialog')).toBeNull();
});
it.each(['directory', 'meanings'])('%s 读取 403 立即清理整页草稿、绑定与在途回调，资格重核未返回也不留旧数据', async source => {
  const lateRead = deferred<TermPage<Term>>(), lateWrite = deferred<Response>();
  vi.mocked(context.session.send).mockReturnValueOnce(lateWrite.promise);
  vi.mocked(termApi.meanings).mockResolvedValueOnce(termPage([{ termId: sampleTerm.termId, canonicalName: '另一个含义', description: '含义资料', version: '0' }], '1', 'next'));
  mount(); await screen.findByText('公开释义'); await fireEvent.click(screen.getByRole('button', { name: '查看共享含义' })); await screen.findByText('含义资料');
  await fireEvent.click(screen.getByRole('button', { name: `编辑术语 ${sampleTerm.termId}` })); const dialog = await screen.findByRole('dialog', { name: '编辑术语组' });
  await fireEvent.update(within(dialog).getByLabelText('简短释义'), '旧草稿'); await fireEvent.update(screen.getByLabelText('词典名称筛选'), '旧查询');
  await fireEvent.click(within(dialog).getByRole('button', { name: '确认提交' })); await waitFor(() => expect(context.session.send).toHaveBeenCalledOnce());
  if (source === 'directory') {
    vi.mocked(termApi.query).mockRejectedValueOnce(new TermError(403, 'FORBIDDEN')); await fireEvent.click(screen.getByRole('button', { name: '重新读取词典' }));
  } else {
    vi.mocked(termApi.query).mockReturnValueOnce(lateRead.promise); await fireEvent.click(screen.getByRole('button', { name: '重新读取词典' }));
    vi.mocked(termApi.meanings).mockRejectedValueOnce(new TermError(403, 'FORBIDDEN')); await fireEvent.click(screen.getByRole('button', { name: '继续读取含义' }));
  }
  await screen.findByRole('button', { name: '重新核对词典资格' }); expect(check).toHaveBeenCalledExactlyOnceWith(true);
  expect(screen.queryByText('公开释义')).toBeNull(); expect(screen.queryByText('含义资料')).toBeNull();
  expect(screen.queryByRole('dialog')).toBeNull(); expect(screen.queryByLabelText('词典名称筛选')).toBeNull();
  expect(vi.mocked(context.session.send).mock.calls[0][1]!.signal!.aborted).toBe(true);
  lateWrite.resolve(json({ kind: 'TERM', id: sampleTerm.termId, version: '1', dictionaryRevision: '2', replayed: false }));
  lateRead.resolve(termPage([{ ...sampleTerm, description: '旧目录迟到' }])); await flushPromises();
  expect(screen.queryByText(/已确认词典维护|旧目录迟到/)).toBeNull(); expect(check).toHaveBeenCalledOnce();
});
it.each(['detail', 'target', 'receipt'])('%s 读取 403 同样触发整页清理和一次资格重核', async source => {
  mount(); await screen.findByText('公开释义'); await fireEvent.update(screen.getByLabelText('词典名称筛选'), '旧查询');
  if (source === 'detail') {
    vi.mocked(termApi.detail).mockRejectedValueOnce(new TermError(403, 'FORBIDDEN'));
    await fireEvent.click(screen.getByRole('button', { name: `编辑术语 ${sampleTerm.termId}` }));
  } else if (source === 'target') {
    await fireEvent.click(screen.getByRole('button', { name: '错词映射' })); await screen.findByText(/当前不可用于建议/);
    vi.mocked(termApi.query).mockRejectedValueOnce(new TermError(403, 'FORBIDDEN'));
    await fireEvent.click(screen.getByRole('button', { name: '编辑错词 9' }));
  } else {
    vi.mocked(context.session.send).mockRejectedValueOnce(new ApiError(0)); vi.spyOn(termApi, 'receipt').mockRejectedValueOnce(new TermError(403, 'FORBIDDEN'));
    await fireEvent.click(screen.getByRole('button', { name: `编辑术语 ${sampleTerm.termId}` })); const dialog = await screen.findByRole('dialog', { name: '编辑术语组' });
    await fireEvent.click(within(dialog).getByRole('button', { name: '确认提交' })); await screen.findByText(/提交结果尚未确认/);
    await fireEvent.click(within(dialog).getByRole('button', { name: '重新读取核对' }));
  }
  await screen.findByRole('button', { name: '重新核对词典资格' }); expect(check).toHaveBeenCalledExactlyOnceWith(true);
  expect(screen.queryByRole('dialog')).toBeNull(); expect(screen.queryByText('公开释义')).toBeNull(); expect(screen.queryByLabelText('词典名称筛选')).toBeNull();
  expect(screen.queryByText(/已确认词典维护/)).toBeNull();
});
it('同资格重挂载不循环读取拒绝，主动重核完成后从空草稿恢复而不重放写入', async () => {
  vi.mocked(termApi.query).mockRejectedValueOnce(new TermError(403, 'FORBIDDEN'));
  const view = mount(); await screen.findByRole('button', { name: '重新核对词典资格' }); view.unmount();
  mount(); await screen.findByRole('button', { name: '重新核对词典资格' }); await flushPromises(); expect(termApi.query).toHaveBeenCalledOnce(); expect(check).toHaveBeenCalledOnce();
  check.mockImplementation(() => { context.checking = true; context.codes = []; });
  await fireEvent.click(screen.getByRole('button', { name: '重新核对词典资格' })); await flushPromises();
  expect(termApi.query).toHaveBeenCalledOnce(); expect(context.session.send).not.toHaveBeenCalled();
  context.checking = false; context.codes = ['search:term:read', 'search:term:write']; await screen.findByText('公开释义');
  expect(termApi.query).toHaveBeenCalledTimes(2); expect((screen.getByLabelText('词典名称筛选') as HTMLInputElement).value).toBe('');
  expect(screen.queryByRole('dialog')).toBeNull(); expect(context.session.send).not.toHaveBeenCalled();
});
it('子视图拒绝使在途详情失效，迟到的详情 403 不再次核对或恢复目录', async () => {
  const late = deferred<Term>(); vi.mocked(termApi.detail).mockReturnValueOnce(late.promise);
  vi.mocked(termApi.meanings).mockResolvedValueOnce(termPage([{ termId: sampleTerm.termId, canonicalName: '另一个含义', description: '含义资料', version: '0' }], '1', 'next')).mockRejectedValueOnce(new TermError(403, 'FORBIDDEN'));
  mount(); await screen.findByText('公开释义'); await fireEvent.click(screen.getByRole('button', { name: '查看共享含义' })); await screen.findByText('含义资料');
  await fireEvent.click(screen.getByRole('button', { name: `编辑术语 ${sampleTerm.termId}` })); await waitFor(() => expect(termApi.detail).toHaveBeenCalledOnce());
  await fireEvent.click(screen.getByRole('button', { name: '继续读取含义' })); await screen.findByRole('button', { name: '重新核对词典资格' });
  expect(vi.mocked(termApi.detail).mock.calls[0][3]!.aborted).toBe(true);
  late.reject(new TermError(403, 'FORBIDDEN')); await flushPromises();
  expect(check).toHaveBeenCalledOnce(); expect(screen.queryByRole('dialog')).toBeNull(); expect(screen.queryByText('公开释义')).toBeNull();
});
it('管理词条中的标记按文本显示，不生成远程资源或可执行节点', async () => {
  vi.mocked(termApi.query).mockResolvedValueOnce(termPage([{ ...sampleTerm, canonicalName: '<img src="https://invalid.test/x">', description: '<script>x</script>' }]));
  const view = mount(); await screen.findByText('<script>x</script>'); expect(view.container.querySelector('img,script,iframe')).toBeNull();
});
it('目标停用时可独立停用映射，目标读取失败不允许手填 ID 或保存编辑', async () => {
  mount(); await screen.findByText('公开释义'); await fireEvent.click(screen.getByRole('button', { name: '错词映射' })); await screen.findByText(/当前不可用于建议/);
  vi.mocked(context.session.send).mockResolvedValueOnce(json({ kind: 'CORRECTION', id: '9', version: '1', dictionaryRevision: '2', replayed: false }));
  await fireEvent.click(screen.getByRole('button', { name: '停用错词 9' })); let dialog = await screen.findByRole('dialog', { name: '停用错词映射' }); await fireEvent.click(within(dialog).getByRole('button', { name: '确认提交' })); await screen.findByText(/已确认词典维护/);
  vi.mocked(termApi.query).mockRejectedValueOnce(new TermError(503, 'UNAVAILABLE'));
  await fireEvent.click(screen.getByRole('button', { name: '编辑错词 9' })); dialog = await screen.findByRole('dialog', { name: '编辑错词映射' }); await screen.findByText(/词典服务暂不可用/);
  expect((within(dialog).getByRole('button', { name: '确认提交' }) as HTMLButtonElement).disabled).toBe(true); expect(within(dialog).queryByRole('textbox', { name: /目标 ID/ })).toBeNull();
});
it.each(['identity', 'permission', 'hidden'])('管理 %s 清理目录、表单和迟到保存，不自动恢复写入', async mode => {
  const pending = deferred<Response>(); vi.mocked(context.session.send).mockReturnValueOnce(pending.promise);
  mount(); await screen.findByText('公开释义'); await fireEvent.click(screen.getByRole('button', { name: `编辑术语 ${sampleTerm.termId}` })); const dialog = await screen.findByRole('dialog', { name: '编辑术语组' });
  await fireEvent.update(within(dialog).getByLabelText('简短释义'), '更新释义'); await fireEvent.click(within(dialog).getByRole('button', { name: '确认提交' })); await waitFor(() => expect(context.session.send).toHaveBeenCalledOnce());
  const signal = vi.mocked(context.session.send).mock.calls[0][1]!.signal!;
  if (mode === 'hidden') { vi.spyOn(document, 'visibilityState', 'get').mockReturnValue('hidden'); await fireEvent(document, new Event('visibilitychange')); }
  else if (mode === 'identity') context.state.epoch++;
  else { context.codes = []; context.facts = '2'; }
  await flushPromises(); expect(signal.aborted).toBe(true); pending.resolve(json({ kind: 'TERM', id: sampleTerm.termId, version: '1', dictionaryRevision: '2', replayed: false })); await flushPromises();
  expect(screen.queryByText(/已确认词典维护/)).toBeNull(); expect(screen.queryByRole('dialog')).toBeNull();
  if (mode === 'hidden') {
    vi.spyOn(document, 'visibilityState', 'get').mockReturnValue('visible'); await fireEvent(document, new Event('visibilitychange')); await screen.findByText('公开释义');
    expect(context.session.send).toHaveBeenCalledOnce(); expect(screen.queryByRole('dialog')).toBeNull();
  }
});
