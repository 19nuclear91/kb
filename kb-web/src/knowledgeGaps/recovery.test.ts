import { h, ref, shallowReactive } from 'vue';
import { fireEvent, screen, waitFor, within } from '@testing-library/vue';
import { flushPromises } from '@vue/test-utils';
import { afterEach, beforeEach, expect, it, vi } from 'vitest';
import { Session, type SessionState } from '../session';
import { mountRouter } from '../test/app';
import { deferred, me, storage } from '../test/fixtures';
import { capabilities, detail, entry, history, page, participant, receipt, response, solutionId } from './testSupport';

const context = shallowReactive({ session: {} as Session, state: { phase: 'anonymous', me: null, epoch: 0 } as SessionState });
const codes = ref<string[]>([]);
vi.mock('../SessionProvider', () => ({ useSession: () => context }));
vi.mock('../admin/access', async importOriginal => ({ ...await importOriginal<typeof import('../admin/access')>(), useAdminAccess: () => ({
  status: 'ready', has: (code: string) => codes.value.includes(code), get permissionFactsKey() { return codes.value.join('|'); },
}) }));
import KnowledgeGapDetailPage from '../pages/KnowledgeGapDetailPage.vue';
import KnowledgeGapNewPage from '../pages/KnowledgeGapNewPage.vue';

const target = '9007199254740993';
const none = { assign: false, note: false, returnToReception: false, submitSolution: false, confirm: false, rejectSolution: false, withdraw: false, close: false };
beforeEach(() => { codes.value = []; });
afterEach(() => { Reflect.deleteProperty(document, 'visibilityState'); });
async function connect(next: (path: string, options: RequestInit) => Response | Promise<Response>) {
  let identity = me;
  const saved = storage(), transport = vi.fn((path: string, options: RequestInit) => path === '/api/auth/me' ? response(identity) : next(path, options));
  const session = new Session(saved, transport as unknown as typeof fetch);
  await session.refresh(); transport.mockClear(); context.session = session; context.state = session.getSnapshot();
  session.subscribe(() => { if (context.session === session) context.state = session.getSnapshot(); });
  return { session, saved, transport, setIdentity(value: typeof me) { identity = value; } };
}
const showDetail = () => mountRouter(() => h(KnowledgeGapDetailPage), { path: `/knowledge-gaps/${target}` });
const postCalls = (transport: ReturnType<typeof vi.fn>) => transport.mock.calls.filter(([, options]) => options.method === 'POST');

it('切换目标取消旧读取，迟到描述不能进入新问题，也不能解除新请求隔离', async () => {
  const old = deferred<Response>();
  const { transport } = await connect(path => path === `/api/knowledge-gaps/${target}` ? old.promise
    : path.includes('/history?') ? response(page()) : response(detail({ gapId: 2n, description: '新目标当前描述' })));
  const view = showDetail(); await waitFor(() => expect(transport).toHaveBeenCalledOnce());
  const oldSignal = transport.mock.calls[0][1].signal!;
  await view.router.push('/knowledge-gaps/2'); await screen.findByText('新目标当前描述');
  expect(oldSignal.aborted).toBe(true); old.resolve(response(detail({ description: '旧目标迟到敏感内容' }))); await flushPromises();
  expect(screen.queryByText('旧目标迟到敏感内容')).toBeNull(); expect(postCalls(transport)).toHaveLength(0);
});

it('同一会话 epoch 核对换号期间旧响应只能丢弃，新身份重新读取而非恢复旧描述', async () => {
  const old = deferred<Response>(); let reads = 0;
  const { session, transport, setIdentity } = await connect(path => path.includes('/history?') ? response(page())
    : reads++ === 0 ? old.promise : response(detail({ reporter: participant(2n), description: '新身份可见描述' })));
  showDetail(); await waitFor(() => expect(transport).toHaveBeenCalledOnce()); const oldSignal = transport.mock.calls[0][1].signal!;
  setIdentity({ ...me, user: { ...me.user, userId: '2' } }); await session.refresh(); await screen.findByText('新身份可见描述');
  expect(oldSignal.aborted).toBe(true); old.resolve(response(detail({ description: '旧身份迟到描述' }))); await flushPromises();
  expect(screen.queryByText('旧身份迟到描述')).toBeNull(); expect(postCalls(transport)).toHaveLength(0);
});

it('替换 Session 对象即使 epoch 相同也建立新作用域，不接受旧会话响应', async () => {
  const pending = deferred<Response>(), first = await connect(path => path.includes('/history?') ? response(page()) : pending.promise);
  showDetail(); await waitFor(() => expect(first.transport).toHaveBeenCalledOnce());
  const epoch = first.session.getSnapshot().epoch;
  const second = await connect(path => path.includes('/history?') ? response(page()) : response(detail({ description: '另一会话当前描述' })));
  expect(second.session.getSnapshot().epoch).toBe(epoch); await screen.findByText('另一会话当前描述');
  pending.resolve(response(detail({ description: '被替换会话的旧描述' }))); await flushPromises();
  expect(screen.queryByText('被替换会话的旧描述')).toBeNull();
});

it('隐藏清理描述、方案、资料标题与原因草稿，恢复先核对身份，不重发状态动作', async () => {
  let resumed = false;
  const { transport } = await connect(path => path.includes('/history?') ? response(page([history()])) : response(detail(resumed
    ? { description: '恢复后重读的当前描述' }
    : { description: '隐藏前问题描述', ...{ state: 'AWAITING_CONFIRMATION', version: 2n, assignee: participant(2n),
      capabilities: { ...capabilities, confirm: true, rejectSolution: true }, currentSolution: entry({ text: '隐藏前方案正文',
        referenceState: 'AVAILABLE', reference: { documentId: 7n, versionId: 8n, title: '隐藏前资料标题' } }) } })));
  showDetail(); await screen.findByText('隐藏前方案正文'); await fireEvent.click(screen.getByRole('button', { name: '撤回问题' }));
  const dialog = await screen.findByRole('dialog'); await fireEvent.update(within(dialog).getByRole('textbox', { name: '本次操作原因' }), '隐藏前原因草稿');
  Object.defineProperty(document, 'visibilityState', { configurable: true, value: 'hidden' }); document.dispatchEvent(new Event('visibilitychange')); await flushPromises();
  for (const text of ['隐藏前问题描述', '隐藏前方案正文', '隐藏前资料标题', '隐藏前原因草稿']) expect(document.body.textContent).not.toContain(text);
  resumed = true; Object.defineProperty(document, 'visibilityState', { configurable: true, value: 'visible' }); document.dispatchEvent(new Event('visibilitychange'));
  await screen.findByText('恢复后重读的当前描述'); expect(transport.mock.calls.some(([path]) => path === '/api/auth/me')).toBe(true);
  expect(postCalls(transport)).toHaveLength(0); expect(screen.queryByRole('dialog')).toBeNull();
});

it('收到 handle 撤权事实立即清理处理正文、表单和未决绑定，迟到成功不能恢复', async () => {
  codes.value = ['knowledge:gap:handle']; const pending = deferred<Response>();
  const { transport } = await connect(path => path.endsWith('/notes') ? pending.promise : path.includes('/history?') ? response(page())
    : response(detail({ reporter: participant(2n), assignee: participant(1n), state: 'IN_PROGRESS', version: 1n,
      description: '负责人私有范围描述', capabilities: { ...capabilities, withdraw: false, returnToReception: true, submitSolution: true } })));
  showDetail(); await fireEvent.update(await screen.findByRole('textbox', { name: '共享说明' }), '撤权前未决共享文本');
  await fireEvent.click(screen.getByRole('checkbox', { name: '我明确确认此说明按问题范围分享' }));
  await fireEvent.click(screen.getByRole('button', { name: '提交共享说明' })); await waitFor(() => expect(postCalls(transport)).toHaveLength(1));
  const signal = postCalls(transport)[0][1].signal!; codes.value = []; await flushPromises();
  await screen.findByText('当前问题资格已失效或无法确认，已清理原内容和输入。'); expect(signal.aborted).toBe(true);
  expect(document.body.textContent).not.toContain('负责人私有范围描述'); expect(document.body.textContent).not.toContain('撤权前未决共享文本');
  pending.resolve(response(receipt({ resultVersion: 2n, state: 'IN_PROGRESS' }))); await flushPromises();
  expect(screen.queryByText('操作已提交，正在重读当前问题事实；不代表通知已送达。')).toBeNull(); expect(postCalls(transport)).toHaveLength(1);
});

it('详情所有表单共用同步互斥，确认在途时不能同时追加说明', async () => {
  const pending = deferred<Response>();
  const { transport } = await connect(path => path.endsWith('/confirm') ? pending.promise : path.includes('/history?') ? response(page())
    : response(detail({ state: 'AWAITING_CONFIRMATION', version: 2n, assignee: participant(2n),
      capabilities: { ...capabilities, confirm: true, rejectSolution: true }, currentSolution: entry() })));
  const view = showDetail(); await fireEvent.update(await screen.findByRole('textbox', { name: '共享说明' }), '并发说明');
  await fireEvent.click(screen.getByRole('checkbox', { name: '我明确确认此说明按问题范围分享' }));
  await fireEvent.click(screen.getByRole('button', { name: '确认已解决' })); const dialog = await screen.findByRole('dialog');
  await fireEvent.click(within(dialog).getByRole('checkbox')); await fireEvent.click(within(dialog).getByRole('button', { name: '确认当前方案已解决' }));
  await fireEvent.submit(screen.getByRole('form', { name: '共享问题处理' })); expect(postCalls(transport)).toHaveLength(1);
  expect(postCalls(transport)[0][0]).toBe(`/api/knowledge-gaps/${target}/confirm`);
  view.unmount(); pending.resolve(response(receipt({ resultVersion: 3n, state: 'RESOLVED', solutionId }))); await flushPromises();
});

it('退回结果未知只允许显式核对，404 清理原内容且不自动重发或扩权读队列', async () => {
  let returned = false; codes.value = ['knowledge:gap:handle'];
  const { transport } = await connect(path => {
    if (path.endsWith('/return')) { returned = true; return new Response(null, { status: 503 }); }
    if (path.includes('/history?')) return response(page());
    return returned ? new Response(null, { status: 404 }) : response(detail({ reporter: participant(2n), assignee: participant(1n), state: 'IN_PROGRESS', version: 1n,
      description: '退回前可读业务描述', capabilities: { ...capabilities, withdraw: false, returnToReception: true, submitSolution: true } }));
  });
  showDetail(); await fireEvent.click(await screen.findByRole('button', { name: '退回受理' })); const dialog = await screen.findByRole('dialog');
  await fireEvent.update(within(dialog).getByRole('textbox', { name: '本次操作原因' }), '需要重新受理'); await fireEvent.click(within(dialog).getByRole('checkbox'));
  await fireEvent.click(within(dialog).getByRole('button', { name: '确认本次操作' })); await screen.findByText(/提交结果待核对/);
  expect(postCalls(transport)).toHaveLength(1); await fireEvent.click(within(dialog).getByRole('button', { name: '取消本次操作' }));
  await fireEvent.click(screen.getByRole('button', { name: '重新读取问题' })); await screen.findByText('当前问题不可访问，已清理旧内容，请由当前合格人员核对。');
  expect(document.body.textContent).not.toContain('退回前可读业务描述'); expect(postCalls(transport)).toHaveLength(1);
  expect(transport.mock.calls.some(([path]) => path.startsWith('/api/admin/'))).toBe(false);
});

it('刷新/重新挂载只丢弃本地未知创建绑定，不从存储、URL 或 history 自动重发', async () => {
  const { transport, saved } = await connect(() => { throw new Error('unconfirmed transport'); });
  let view = mountRouter(() => h(KnowledgeGapNewPage), { path: '/knowledge-gaps/new' });
  await fireEvent.update(screen.getByRole('textbox', { name: '明确分享的描述' }), '只在当前身份内存的未知草稿');
  await fireEvent.click(screen.getByRole('checkbox', { name: '我已预览并明确确认上述分享与保留范围' })); await fireEvent.click(screen.getByRole('button', { name: '提交问题' }));
  await screen.findByText(/提交结果待核对/); expect(postCalls(transport)).toHaveLength(1);
  expect(saved.setItem).not.toHaveBeenCalled(); expect(JSON.stringify([...saved.values])).not.toContain('未知草稿');
  expect(JSON.stringify(view.router.options.history.state)).not.toContain('未知草稿'); expect(view.router.currentRoute.value.fullPath).toBe('/knowledge-gaps/new');
  view.unmount(); view = mountRouter(() => h(KnowledgeGapNewPage), { path: '/knowledge-gaps/new' }); await flushPromises();
  expect((screen.getByRole('textbox', { name: '明确分享的描述' }) as HTMLTextAreaElement).value).toBe('');
  expect(screen.queryByRole('button', { name: '按原内容重试' })).toBeNull(); expect(postCalls(transport)).toHaveLength(1);
});

it('描述、说明和标签仅纯文本显示，不执行脚本、HTML 或加载远程图片', async () => {
  const description = '<script>window.__gapScriptExecuted = true</script>', text = '<img src="https://external.test/private" onerror="alert(1)">';
  const { transport } = await connect(path => path.includes('/history?') ? response(page()) : response(detail({ description,
    state: 'AWAITING_CONFIRMATION', version: 2n, assignee: participant(2n), reporter: { userId: 1n, label: '<b>标签不是 HTML</b>' },
    capabilities: { ...capabilities, confirm: true, rejectSolution: true }, currentSolution: entry({ text }) })));
  showDetail(); await screen.findByText(description); await screen.findByText(text);
  expect((window as unknown as Record<string, unknown>).__gapScriptExecuted).toBeUndefined();
  expect(document.querySelector('img[src="https://external.test/private"]')).toBeNull();
  expect([...document.querySelectorAll('b')].some(node => node.textContent === '标签不是 HTML')).toBe(false);
  expect(document.body.textContent).toContain('<b>标签不是 HTML</b>'); expect(transport.mock.calls.every(([path]) => path.startsWith('/api/'))).toBe(true);
});
