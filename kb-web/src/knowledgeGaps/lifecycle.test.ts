import { h, shallowReactive } from 'vue';
import { fireEvent, screen, waitFor, within } from '@testing-library/vue';
import { flushPromises } from '@vue/test-utils';
import { expect, it, vi } from 'vitest';
import type { Session, SessionState } from '../session';
import { mountRouter } from '../test/app';
import { capabilities, detail, entry, history, participant, ready, receipt, response, solutionId } from './testSupport';

const context = shallowReactive({ session: {} as Session, state: { phase: 'anonymous', me: null, epoch: 0 } as SessionState });
vi.mock('../SessionProvider', () => ({ useSession: () => context }));
import GapDetailWorkspace from './GapDetailWorkspace.vue';

const target = '9007199254740993', nextEvent = '33333333-4444-5555-6666-777777777777';
const none = { assign: false, note: false, returnToReception: false, submitSolution: false, confirm: false, rejectSolution: false, withdraw: false, close: false };
const awaiting = { state: 'AWAITING_CONFIRMATION', version: 2n, assignee: participant(2n),
  capabilities: { ...capabilities, confirm: true, rejectSolution: true }, currentSolution: entry() };
async function connect(next: (path: string, options: RequestInit) => Response | Promise<Response>) {
  const value = await ready(next); context.session = value.session; context.state = value.session.getSnapshot();
  value.session.subscribe(() => { context.state = value.session.getSnapshot(); }); return value;
}
function show() { return mountRouter(() => h(GapDetailWorkspace, { gapId: target }), { path: `/knowledge-gaps/${target}` }); }
function writes(transport: ReturnType<typeof vi.fn>, suffix: string) { return transport.mock.calls.filter(([path]) => path.endsWith(suffix)); }

it('只有本人明确针对当前方案确认，加载、资料存在和打开对话框都不自动解决', async () => {
  let resolved = false;
  const { transport } = await connect(path => {
    if (path.endsWith('/confirm')) { resolved = true; return response(receipt({ resultVersion: 3n, state: 'RESOLVED', solutionId })); }
    if (path.includes('/history?')) return response(pageHistory());
    return response(detail(resolved ? { ...awaiting, state: 'RESOLVED', version: 3n, capabilities: none, currentSolution: null } : awaiting));
  });
  show(); await screen.findByText('人工说明'); await flushPromises(); expect(writes(transport, '/confirm')).toHaveLength(0);
  await fireEvent.click(screen.getByRole('button', { name: '确认已解决' }));
  const dialog = await screen.findByRole('dialog', { name: '确认当前方案已解决' });
  const submit = within(dialog).getByRole('button', { name: '确认当前方案已解决' }) as HTMLButtonElement;
  expect(submit.disabled).toBe(true); expect(writes(transport, '/confirm')).toHaveLength(0);
  await fireEvent.click(within(dialog).getByRole('checkbox', { name: '我明确确认当前方案解决了我的问题' }));
  await fireEvent.click(submit); await screen.findByText(/问题 9007199254740993 · 已解决/);
  expect(writes(transport, '/confirm')[0][1].body).toBe(`{"expectedVersion":2,"solutionId":"${solutionId}"}`);
  expect(writes(transport, '/confirm')).toHaveLength(1); expect(screen.queryByRole('button', { name: '确认已解决' })).toBeNull();
});
const pageHistory = () => ({ items: [history()], hasMore: false, nextCursor: null });

it('非提问者即使兼任处理人或响应能力被误标也不显示本人确认/撤回', async () => {
  await connect(path => path.includes('/history?') ? response(pageHistory()) : response(detail({ ...awaiting,
    reporter: participant(2n), assignee: participant(1n), currentSolution: entry({ author: participant(1n) }),
    capabilities: { ...awaiting.capabilities, returnToReception: true } })));
  show(); await screen.findByText('人工说明');
  expect(screen.queryByRole('button', { name: '确认已解决' })).toBeNull();
  expect(screen.queryByRole('button', { name: '仍未解决' })).toBeNull();
  expect(screen.queryByRole('button', { name: '撤回问题' })).toBeNull();
  expect(screen.getByRole('button', { name: '退回受理' })).toBeTruthy();
});

it('资料不可用不能宣称确认成功，但仍可带原因退回方案并保留历史', async () => {
  let rejected = false;
  const rejectionEntry = entry({ entryId: nextEvent, kind: 'REJECT_SOLUTION', position: 3n, author: participant(), text: '原资料不可读仍未解决' });
  const { transport } = await connect(path => {
    if (path.endsWith('/reject-solution')) { rejected = true; return response(receipt({ resultVersion: 3n, state: 'IN_PROGRESS', solutionId })); }
    if (path.includes('/history?')) return response(rejected ? { items: [history(), history({ eventId: nextEvent,
      kind: 'GAP_SOLUTION_REJECTED', resultVersion: 3n, solutionId, entry: rejectionEntry })], hasMore: false, nextCursor: null } : pageHistory());
    return response(detail(rejected ? { ...awaiting, state: 'IN_PROGRESS', version: 3n, currentSolution: null, capabilities }
      : { ...awaiting, currentSolution: entry({ referenceState: 'UNAVAILABLE' }) }));
  });
  show(); await screen.findByText('人工说明');
  expect((screen.getByRole('button', { name: '确认已解决' }) as HTMLButtonElement).disabled).toBe(true);
  await fireEvent.click(screen.getByRole('button', { name: '仍未解决' }));
  const dialog = await screen.findByRole('dialog', { name: '仍未解决' });
  await fireEvent.update(within(dialog).getByRole('textbox', { name: '本次操作原因' }), '原资料不可读仍未解决');
  await fireEvent.click(within(dialog).getByRole('checkbox')); await fireEvent.click(within(dialog).getByRole('button', { name: '确认本次操作' }));
  await screen.findByText('原资料不可读仍未解决');
  expect(screen.getByText(/问题 9007199254740993 · 处理中/)).toBeTruthy();
  expect(writes(transport, '/reject-solution')[0][1].body).toBe(`{"expectedVersion":2,"solutionId":"${solutionId}","reason":"原资料不可读仍未解决"}`);
  expect(transport.mock.calls.some(([path]) => path.startsWith('/api/documents/'))).toBe(false);
});

it('旧方案确认 409 后重读新事实，清理旧对话框，不自动确认新方案', async () => {
  let changed = false;
  const { transport } = await connect(path => {
    if (path.endsWith('/confirm')) { changed = true; return new Response(null, { status: 409 }); }
    if (path.includes('/history?')) return response(pageHistory());
    return response(detail(changed ? { ...awaiting, version: 4n,
      currentSolution: entry({ entryId: nextEvent, position: 4n, text: '后来提交的新方案' }) } : awaiting));
  });
  show(); await fireEvent.click(await screen.findByRole('button', { name: '确认已解决' }));
  const dialog = await screen.findByRole('dialog'); await fireEvent.click(within(dialog).getByRole('checkbox'));
  await fireEvent.click(within(dialog).getByRole('button', { name: '确认当前方案已解决' }));
  await screen.findByText('后来提交的新方案');
  expect(screen.queryByRole('dialog')).toBeNull(); expect(writes(transport, '/confirm')).toHaveLength(1);
  expect(writes(transport, '/confirm')[0][1].body).toContain(`"solutionId":"${solutionId}"`);
  expect(screen.queryByRole('button', { name: '按原状态操作重试' })).toBeNull();
});

it('撤回需原因及明确保留确认，显示已撤回而非已删除/解决', async () => {
  let withdrawn = false;
  const { transport } = await connect(path => {
    if (path.endsWith('/withdraw')) { withdrawn = true; return response(receipt({ resultVersion: 1n, state: 'WITHDRAWN' })); }
    if (path.includes('/history?')) return response(pageHistory());
    return response(detail(withdrawn ? { state: 'WITHDRAWN', version: 1n, capabilities: none } : {}));
  });
  show(); await fireEvent.click(await screen.findByRole('button', { name: '撤回问题' }));
  const dialog = await screen.findByRole('dialog', { name: '撤回问题' });
  expect(within(dialog).getByText(/撤回停止处理，不等于删除/)).toBeTruthy();
  expect((within(dialog).getByRole('button', { name: '确认本次操作' }) as HTMLButtonElement).disabled).toBe(true);
  await fireEvent.update(within(dialog).getByRole('textbox', { name: '本次操作原因' }), '不再需要处理');
  await fireEvent.click(within(dialog).getByRole('checkbox')); await fireEvent.click(within(dialog).getByRole('button', { name: '确认本次操作' }));
  await screen.findByText(/问题 9007199254740993 · 已撤回/);
  expect(writes(transport, '/withdraw')[0][1].body).toBe('{"expectedVersion":0,"reason":"不再需要处理"}');
  expect(screen.queryByText('已删除')).toBeNull();
});

it('负责人退回后下一次 404 清理正文和旧方案，不回退统一队列或延续历史访问', async () => {
  let returned = false;
  const { transport } = await connect(path => {
    if (path.endsWith('/return')) { returned = true; return response(receipt({ resultVersion: 3n, state: 'SUBMITTED' })); }
    if (path.includes('/history?')) return response(pageHistory());
    return returned ? new Response(null, { status: 404 }) : response(detail({ ...awaiting, reporter: participant(2n), assignee: participant(1n),
      currentSolution: entry({ author: participant(1n) }), capabilities: { ...capabilities, withdraw: false, returnToReception: true } }));
  });
  show(); await fireEvent.click(await screen.findByRole('button', { name: '退回受理' }));
  const dialog = await screen.findByRole('dialog', { name: '退回统一受理' });
  expect(within(dialog).getByText(/你可能不再有此问题访问权/)).toBeTruthy();
  await fireEvent.update(within(dialog).getByRole('textbox', { name: '本次操作原因' }), '需要受理人员重新分派');
  await fireEvent.click(within(dialog).getByRole('checkbox')); await fireEvent.click(within(dialog).getByRole('button', { name: '确认本次操作' }));
  await screen.findByText('当前问题不可访问，已清理旧内容，请由当前合格人员核对。');
  expect(screen.queryByText('明确分享的描述')).toBeNull(); expect(screen.queryByText('人工说明')).toBeNull();
  expect(transport.mock.calls.some(([path]) => path.startsWith('/api/admin/'))).toBe(false); expect(writes(transport, '/return')).toHaveLength(1);
});

it.each(['RESOLVED', 'CLOSED', 'WITHDRAWN'])('终态 %s 只读、不可重开或追加，新需求不自动复制描述', async state => {
  const { transport } = await connect(path => path.includes('/history?') ? response(pageHistory()) : response(detail({ state, version: 3n, capabilities: none })));
  const view = show(); await screen.findByText('明确分享的描述');
  for (const name of ['确认已解决', '仍未解决', '退回受理', '撤回问题', '准备解决方案', '提交共享说明', '重开', '删除问题']) {
    expect(screen.queryByRole('button', { name })).toBeNull();
  }
  await fireEvent.click(screen.getByRole('link', { name: '提出新的需求' }));
  await waitFor(() => expect(view.router.currentRoute.value.fullPath).toBe('/knowledge-gaps/new'));
  expect(transport.mock.calls.every(([, options]) => !options.method || options.method === 'GET')).toBe(true);
});

it('取消未发送的状态操作清空原因，零提交，不持久保存或伪造撤回', async () => {
  const { transport, saved } = await connect(path => path.includes('/history?') ? response(pageHistory()) : response(detail()));
  show(); await fireEvent.click(await screen.findByRole('button', { name: '撤回问题' }));
  let dialog = await screen.findByRole('dialog'); await fireEvent.update(within(dialog).getByRole('textbox', { name: '本次操作原因' }), '取消的原因草稿');
  await fireEvent.click(within(dialog).getByRole('button', { name: '取消本次操作' })); await flushPromises();
  expect(screen.queryByRole('dialog')).toBeNull(); expect(writes(transport, '/withdraw')).toHaveLength(0); expect(saved.setItem).not.toHaveBeenCalled();
  await fireEvent.click(screen.getByRole('button', { name: '撤回问题' })); dialog = await screen.findByRole('dialog');
  expect((within(dialog).getByRole('textbox', { name: '本次操作原因' }) as HTMLTextAreaElement).value).toBe('');
});
