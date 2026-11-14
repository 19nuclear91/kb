import { h } from 'vue';
import { fireEvent, screen, waitFor, within } from '@testing-library/vue';
import { flushPromises } from '@vue/test-utils';
import { expect, it, vi } from 'vitest';
import App from '../App.vue';
import { Session } from '../session';
import { mountRouter } from '../test/app';
import { deferred, me, storage } from '../test/fixtures';
import { detail, entry, history, page, participant, receipt, response, solutionId } from '../knowledgeGaps/testSupport';

const none = { assign: false, note: false, returnToReception: false, submitSolution: false, confirm: false, rejectSolution: false, withdraw: false, close: false };
const operations = { ...none, assign: true, close: true, note: true };
function harness(read: (path: string, options: RequestInit) => Response | Promise<Response>, initialCodes = ['knowledge:gap:read', 'knowledge:gap:assign', 'knowledge:gap:close']) {
  let codes = initialCodes;
  const transport = vi.fn(async (url: RequestInfo | URL, options: RequestInit = {}) => {
    const path = String(url);
    if (path === '/api/auth/me') return response(me);
    if (path === '/api/auth/permissions') return response(codes);
    if (path === '/api/notifications/unread-count') return response({ count: '0', asOf: '2026-11-14T00:00:00Z' });
    if (path.includes('/history?')) return response(page([history()]));
    return read(path, options);
  });
  const session = new Session(storage(), transport as typeof fetch), view = mountRouter(() => h(App, { session }), { path: '/admin/knowledge-gaps/123' });
  return { transport, session, view, setCodes(value: string[]) { codes = value; } };
}
const base = (over = {}) => detail({ gapId: 123n, reporter: participant(2n), capabilities: operations, ...over });
async function selectAssignee() {
  await fireEvent.click(await screen.findByRole('button', { name: '分派或改派' })); const dialog = await screen.findByRole('dialog', { name: '确认人工分派' });
  await fireEvent.update(within(dialog).getByRole('textbox', { name: '处理人名称' }), '  合格人员名称  ');
  await fireEvent.click(within(dialog).getByRole('button', { name: '查询合格处理人' }));
  await fireEvent.click(await within(dialog).findByRole('button', { name: '选择此负责人' }));
  await fireEvent.click(within(dialog).getByRole('checkbox')); return dialog;
}
it('人员选择只调用问题候选 POST，改派使旧方案失效但不删除历史', async () => {
  let assigned = false;
  const h = harness(path => path.endsWith('/assignees/search') ? response(page([participant(8n)]))
    : path.endsWith('/assign') ? (assigned = true, response(receipt({ gapId: 123n, resultVersion: 3n, state: 'IN_PROGRESS' })))
      : response(base(assigned ? { state: 'IN_PROGRESS', version: 3n, assignee: participant(8n) }
        : { state: 'AWAITING_CONFIRMATION', version: 2n, assignee: participant(2n), currentSolution: entry() })));
  const dialog = await selectAssignee(); expect(within(dialog).getByText(/改派将使原待确认方案失效/)).toBeTruthy();
  await fireEvent.click(within(dialog).getByRole('button', { name: '确认分派' })); await screen.findByText(/问题 123 · 处理中/);
  expect(screen.queryByText('人工说明')).toBeNull();
  const search = h.transport.mock.calls.find(([path]) => String(path).endsWith('/assignees/search'))!;
  expect(search[0]).toBe('/api/admin/knowledge-gaps/123/assignees/search');
  expect(search[1]?.body).toBe('{"nameQuery":"合格人员名称","limit":20,"cursor":null}');
  expect(h.transport.mock.calls.find(([path]) => String(path).endsWith('/assign'))![1]?.body).toBe('{"expectedVersion":2,"assigneeUserId":8}');
  expect(h.transport.mock.calls.some(([path]) => /\/api\/admin\/(identity|spaces)/.test(String(path)))).toBe(false);
});
it('候选失效 409 清理选择并重读，不自动再次分派或回退身份目录', async () => {
  const h = harness(path => path.endsWith('/assignees/search') ? response(page([participant(8n)])) : path.endsWith('/assign') ? new Response(null, { status: 409 }) : response(base()));
  const dialog = await selectAssignee(); await fireEvent.click(within(dialog).getByRole('button', { name: '确认分派' }));
  await screen.findByText('问题、方案或候选已变化，已清理旧输入，请重读后重新确认。');
  expect(screen.queryByRole('dialog')).toBeNull(); expect(h.transport.mock.calls.filter(([path]) => String(path).endsWith('/assign'))).toHaveLength(1);
});
it('带原因关闭明确不是本人解决，关闭终态不提供处理或确认', async () => {
  let closed = false;
  const h = harness(path => path.endsWith('/close') ? (closed = true, response(receipt({ gapId: 123n, resultVersion: 1n, state: 'CLOSED' })))
    : response(base(closed ? { state: 'CLOSED', version: 1n, capabilities: none } : {})));
  await fireEvent.click(await screen.findByRole('button', { name: '带原因关闭问题' })); const dialog = await screen.findByRole('dialog', { name: '确认带原因关闭' });
  expect(within(dialog).getByText(/关闭不等于提问者确认解决/)).toBeTruthy();
  await fireEvent.update(within(dialog).getByRole('textbox', { name: '关闭原因' }), '无法继续确认，人工关闭');
  expect((within(dialog).getByRole('button', { name: '确认关闭' }) as HTMLButtonElement).disabled).toBe(true);
  await fireEvent.click(within(dialog).getByRole('checkbox')); await fireEvent.click(within(dialog).getByRole('button', { name: '确认关闭' }));
  await screen.findByText(/问题 123 · 已关闭/); expect(screen.queryByRole('button', { name: '确认已解决' })).toBeNull();
  expect(h.transport.mock.calls.find(([path]) => String(path).endsWith('/close'))![1]?.body).toBe('{"expectedVersion":0,"reason":"无法继续确认，人工关闭"}');
});
it('未知结果不自动重发，显式重试保留同一版本、原载荷和请求标识', async () => {
  let writes = 0;
  const h = harness(path => path.endsWith('/assignees/search') ? response(page([participant(8n)]))
    : path.endsWith('/assign') ? ++writes === 1 ? new Response(null, { status: 503 }) : response(receipt({ gapId: 123n, resultVersion: 1n, state: 'IN_PROGRESS', replayed: true }))
      : response(base(writes ? { state: 'IN_PROGRESS', version: 1n, assignee: participant(8n) } : {})));
  const dialog = await selectAssignee(); await fireEvent.click(within(dialog).getByRole('button', { name: '确认分派' }));
  await screen.findByText(/提交结果待核对/); expect(writes).toBe(1);
  await fireEvent.click(within(dialog).getByRole('button', { name: '按原受理内容重试' })); await screen.findByText(/问题 123 · 处理中/);
  const calls = h.transport.mock.calls.filter(([path]) => String(path).endsWith('/assign'));
  expect(calls[0][1]?.body).toBe(calls[1][1]?.body);
  expect(new Headers(calls[0][1]?.headers).get('X-Request-Id')).toBe(new Headers(calls[1][1]?.headers).get('X-Request-Id'));
});
it('只有 read 不能分派/关闭，资格变化清理在途候选和旧对话框', async () => {
  const first = harness(() => response(base()), ['knowledge:gap:read']); await screen.findByText('明确分享的描述');
  expect(screen.queryByRole('button', { name: '分派或改派' })).toBeNull(); expect(screen.queryByRole('button', { name: '带原因关闭问题' })).toBeNull(); first.view.unmount();
  const pending = deferred<Response>(), h = harness(path => path.endsWith('/assignees/search') ? pending.promise : response(base()));
  await fireEvent.click(await screen.findByRole('button', { name: '分派或改派' })); const dialog = await screen.findByRole('dialog');
  await fireEvent.click(within(dialog).getByRole('button', { name: '查询合格处理人' }));
  await waitFor(() => expect(h.transport.mock.calls.some(([path]) => String(path).endsWith('/assignees/search'))).toBe(true));
  const signal = h.transport.mock.calls.find(([path]) => String(path).endsWith('/assignees/search'))![1]?.signal!;
  h.setCodes(['knowledge:gap:read']); await h.session.refresh(); await screen.findByText('明确分享的描述'); expect(signal.aborted).toBe(true);
  pending.resolve(response(page([participant(8n)]))); await flushPromises(); expect(screen.queryByRole('dialog')).toBeNull();
  expect(screen.queryByRole('button', { name: '选择此负责人' })).toBeNull();
});
