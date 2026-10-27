import { beforeEach, expect, it, vi } from 'vitest';
import { act, fireEvent, render, screen, waitFor, within } from '@testing-library/react';
import { MemoryRouter } from 'react-router-dom';
import { ApiError } from './api';
import { deferred, me, turn } from './test/fixtures';
import type { Session } from './session';
const context = vi.hoisted(() => ({ session: {} as Session, state: { phase: 'ready', me: null as typeof me | null, epoch: 1 } }));
vi.mock('./SessionProvider', () => ({ useSession: () => context }));
vi.mock('./stream', () => ({ openTurn: vi.fn() }));
import { openTurn } from './stream';
import { AssistantPage, AssistantWorkspace, ConversationPanel } from './pages/Assistant';
const meta = { id: '1', title: '目标会话', createdAt: '2026-01-02', lastTurnSeq: 0n, activeTurnId: null };
beforeEach(() => {
  context.state = { phase: 'ready', me, epoch: 1 };
  context.session = { json: vi.fn(async (path: string) => path.includes('/turns/') ? turn : path.includes('/turns?') ? { items: [], nextCursor: null } : meta),
    post: vi.fn(), getSnapshot: () => context.state } as unknown as Session;
  vi.mocked(openTurn).mockReset();
});
async function ask() { await screen.findByLabelText('问题'); fireEvent.change(screen.getByLabelText('问题'), { target: { value: '本次问题' } }); fireEvent.click(screen.getByRole('button', { name: '发送问题' })); }
it('缺省关闭不调用任何会话请求，开启消费替身数据', async () => {
  const view = render(<AssistantPage />); expect(screen.getByText('会话功能暂未开放')).toBeTruthy(); expect(context.session.json).not.toHaveBeenCalled(); expect(openTurn).not.toHaveBeenCalled(); view.unmount();
  vi.mocked(context.session.json).mockResolvedValue({ items: [meta], nextCursor: null }); render(<MemoryRouter><AssistantPage enabled /></MemoryRouter>); await screen.findByText('目标会话'); expect(context.session.json).toHaveBeenCalledWith('/api/conversations?limit=20', expect.anything(), false);
});
it('创建结果丢失后匹配重试使用同一规范化标题及标识；分页保持服务器顺序', async () => {
  vi.mocked(context.session.json).mockImplementation(async path => (path.includes('cursor=') ? { items: [{ ...meta, id: '2', title: '较早会话', createdAt: '2026-01-01', updatedAt: '2026-10-01' }], nextCursor: null } : { items: [meta], nextCursor: 'opaque-cursor' }) as never);
  vi.mocked(context.session.post).mockRejectedValueOnce(new ApiError(0)).mockResolvedValueOnce({ conversation: { ...meta, id: '3', title: '显式标题', createdAt: '2026-01-03' }, replayed: true });
  render(<MemoryRouter><AssistantWorkspace /></MemoryRouter>); await screen.findByText('目标会话'); fireEvent.click(screen.getByText('更多会话')); await screen.findByText('较早会话');
  expect(screen.getAllByRole('link').map(node => node.textContent)).toEqual(['目标会话', '较早会话']);
  fireEvent.click(screen.getByText('新建会话')); fireEvent.change(screen.getByLabelText('标题（可选）'), { target: { value: '  显式标题  ' } }); fireEvent.click(screen.getByRole('button', { name: /^创\s*建$/ })); await screen.findByText('网络结果尚未确认，请稍后核对。');
  fireEvent.click(screen.getByRole('button', { name: /^创\s*建$/ })); await screen.findByText('显式标题'); const calls = vi.mocked(context.session.post).mock.calls;
  expect(calls[0]).toEqual(calls[1]); expect(calls[0][1]).toEqual({ title: '显式标题', requestId: expect.any(String) }); expect(screen.getAllByRole('link').map(node => node.textContent)).toEqual(['显式标题', '目标会话', '较早会话']);
});
it('缺省标题交给服务器，不从正文生成', async () => {
  vi.mocked(context.session.json).mockResolvedValue({ items: [], nextCursor: null }); vi.mocked(context.session.post).mockResolvedValue({ conversation: { ...meta, title: '新会话' } });
  render(<MemoryRouter><AssistantWorkspace /></MemoryRouter>); await screen.findByText('创建你的第一个会话'); fireEvent.click(screen.getByText('新建会话')); fireEvent.click(screen.getByRole('button', { name: /^创\s*建$/ })); await screen.findByText('新会话'); expect(vi.mocked(context.session.post).mock.calls[0][1]).toMatchObject({ title: undefined });
});
it('503 不伪造回答，保留输入；相同不确定重试保持三元组', async () => {
  vi.mocked(openTurn).mockRejectedValue(new ApiError(503)); render(<ConversationPanel conversationId="1" onDeleted={vi.fn()} />); await ask();
  await screen.findByText('生成服务暂不可用，请稍后重试。'); expect((screen.getByLabelText('问题') as HTMLTextAreaElement).value).toBe('本次问题'); expect(screen.queryByText('已完成')).toBeNull();
  fireEvent.click(screen.getByRole('button', { name: '匹配重试' })); await waitFor(() => expect(openTurn).toHaveBeenCalledTimes(2)); const calls = vi.mocked(openTurn).mock.calls; expect(calls[0][2]).toBe(calls[1][2]); expect(context.session.json).toHaveBeenCalledTimes(2);
});
it('409 刷新事实保留输入，不循环新建请求；单个 404 不改变全局功能', async () => {
  vi.mocked(openTurn).mockRejectedValue(new ApiError(409)); render(<ConversationPanel conversationId="1" onDeleted={vi.fn()} />); await ask(); await screen.findByText('状态已变化，请刷新后确认。');
  await waitFor(() => expect(context.session.json).toHaveBeenCalledTimes(4)); expect((screen.getByLabelText('问题') as HTMLTextAreaElement).value).toBe('本次问题'); expect(openTurn).toHaveBeenCalledOnce();
  vi.mocked(context.session.json).mockRejectedValue(new ApiError(404)); fireEvent.click(screen.getByText('刷新历史')); await screen.findByText('目标不存在或当前不可访问。'); expect(screen.queryByLabelText('问题')).toBeNull(); expect(screen.queryByText('会话功能暂未开放')).toBeNull();
});
it.each(['done', 'error', 'eof', 'replay'] as const)('%s 后用持久前缀替换临时尾部，且只提交一次', async result => {
  vi.mocked(openTurn).mockImplementation(async (_session, _id, _binding, _signal, started, emit) => { started(turn.id); emit({ type: 'delta', text: '未保存临时尾部' }); return result; });
  render(<ConversationPanel conversationId="1" onDeleted={vi.fn()} />); await ask(); await screen.findByText('已保存前缀'); expect(screen.queryByText('未保存临时尾部')).toBeNull(); expect(screen.getByText('已停止 · 未完成')).toBeTruthy(); expect(openTurn).toHaveBeenCalledOnce();
  expect(vi.mocked(context.session.json).mock.calls.filter(call => call[0].includes(`/turns/${turn.id}`))).toHaveLength(1);
});
it('停止关闭流，完成与取消竞争采用服务端完成状态', async () => {
  let signal!: AbortSignal;
  vi.mocked(context.session.json).mockImplementation(async path => (path.includes('/turns/') ? { ...turn, status: 'COMPLETED' } : path.includes('/turns?') ? { items: [], nextCursor: null } : meta) as never);
  vi.mocked(openTurn).mockImplementation(async (_session, _id, _binding, incoming, started, emit) => { signal = incoming; started(turn.id); emit({ type: 'delta', text: '临时回答' }); return new Promise((_yes, no) => incoming.addEventListener('abort', () => no(new DOMException('停止', 'AbortError')), { once: true })); });
  const view = render(<ConversationPanel conversationId="1" onDeleted={vi.fn()} />); await ask(); await screen.findByText('临时回答'); fireEvent.click(screen.getByRole('button', { name: /^停\s*止$/ })); await screen.findByText('已完成'); expect(signal.aborted).toBe(true); expect(screen.queryByText('临时回答')).toBeNull(); view.unmount();
});
it('离开或退出中断连接，迟到事件不更新、不继续保护核对', async () => {
  const pending = deferred<'eof'>(); let signal!: AbortSignal;
  vi.mocked(openTurn).mockImplementation(async (_session, _id, _binding, incoming, started) => { signal = incoming; started(turn.id); return pending.promise; });
  const view = render(<ConversationPanel conversationId="1" onDeleted={vi.fn()} />); await ask(); await waitFor(() => expect(openTurn).toHaveBeenCalledOnce()); view.unmount(); expect(signal.aborted).toBe(true); context.state.epoch++;
  await act(async () => pending.resolve('eof')); expect(context.session.json).toHaveBeenCalledTimes(2);
});
it('删除先关闭流，204 与重复 204 成功，迟到流和历史不能恢复内容', async () => {
  const pending = deferred<'eof'>(), deleted = vi.fn(); let signal!: AbortSignal;
  vi.mocked(openTurn).mockImplementation(async (_session, _id, _binding, incoming, started, emit) => { signal = incoming; started(turn.id); emit({ type: 'delta', text: '旧临时正文' }); return pending.promise; });
  vi.mocked(context.session.json).mockImplementation(async (path, options) => {
    if (options?.method === 'DELETE') { expect(signal.aborted).toBe(true); return undefined as never; }
    return (path.includes('/turns?') ? { items: [], nextCursor: null } : meta) as never;
  });
  render(<ConversationPanel conversationId="1" onDeleted={deleted} />); await ask(); await screen.findByText('旧临时正文'); fireEvent.click(screen.getByRole('button', { name: '删除会话' })); const dialog = await screen.findByRole('dialog', { name: '删除会话「目标会话」？' });
  fireEvent.click(within(dialog).getByRole('button', { name: '删除会话' })); await waitFor(() => expect(deleted).toHaveBeenCalledOnce()); await act(async () => pending.resolve('eof')); expect(screen.queryByText('旧临时正文')).toBeNull();
  expect(vi.mocked(context.session.json).mock.calls.filter(call => call[1]?.method === 'DELETE')).toHaveLength(1); expect(vi.mocked(context.session.json).mock.calls.filter(call => call[0].includes('/turns/'))).toHaveLength(0);
  fireEvent.click(screen.getByRole('button', { name: '删除会话' })); fireEvent.click(within(await screen.findByRole('dialog')).getByRole('button', { name: /删除会话/ })); await waitFor(() => expect(deleted).toHaveBeenCalledTimes(2));
});
it('已有活动轮次限制新的提交，不把刷新当作再次生成', async () => {
  vi.mocked(context.session.json).mockImplementation(async path => (path.includes('/turns?') ? { items: [{ ...turn, status: 'RUNNING' }], nextCursor: null } : { ...meta, activeTurnId: turn.id }) as never);
  render(<ConversationPanel conversationId="1" onDeleted={vi.fn()} />); await ask(); expect(openTurn).not.toHaveBeenCalled(); expect((screen.getByRole('button', { name: '发送问题' }) as HTMLButtonElement).disabled).toBe(true);
});
it('删除不确定仅提示核对，不声称服务器删除成功', async () => {
  vi.mocked(context.session.json).mockImplementation(async (path, options) => { if (options?.method === 'DELETE') throw new ApiError(0); return (path.includes('/turns?') ? { items: [turn], nextCursor: null } : meta) as never; });
  const deleted = vi.fn(); render(<ConversationPanel conversationId="1" onDeleted={deleted} />); await screen.findByText('已保存前缀'); fireEvent.click(screen.getByRole('button', { name: '删除会话' }));
  fireEvent.click(within(await screen.findByRole('dialog')).getByRole('button', { name: '删除会话' })); await screen.findByText('删除结果未确认，请刷新核对；不会自动重复删除。'); expect(deleted).not.toHaveBeenCalled(); expect(screen.queryByText('已保存前缀')).toBeNull();
});
