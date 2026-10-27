import { beforeEach, expect, it, vi } from 'vitest';
import { act, fireEvent, render, screen, within } from '@testing-library/react';
import { deferred, json } from '../test/fixtures';
import { CommentPanel } from './CommentPanel';

const h = vi.hoisted(() => ({ json: vi.fn(), send: vi.fn(), refresh: vi.fn(), state: { phase: 'ready', epoch: 1 } }));
const session = { json: h.json, send: h.send, refresh: h.refresh, getSnapshot: () => h.state };
vi.mock('../SessionProvider', () => ({ useSession: () => ({ session, state: h.state }) }));

const id = (n: number) => `dddddddd-0000-0000-0000-${String(n).padStart(12, '0')}`;
const own = (n: number) => ({ commentId: id(n), parentId: null, documentId: '10', versionId: '21', createdAt: '2026-10-26T00:00:00Z',
  deleted: false, author: { userId: '7', label: 'A 用户' }, body: `A 的正文${n}`, isOwn: true, canDelete: true });
const listing = (items: unknown[]) => ({ items, hasMore: false, nextCursor: null, canCreate: true });
const panel = () => <CommentPanel documentId="10" versionId="21" />;
beforeEach(() => {
  h.state = { phase: 'ready', epoch: h.state.epoch + 1 };
  h.json.mockReset().mockResolvedValue(listing([own(1)]));
  h.send.mockReset(); h.refresh.mockReset().mockResolvedValue(undefined);
});

it('隐藏立即卸载正文、草稿与删除目标；恢复先核对身份，后台不读取', async () => {
  const visibility = vi.spyOn(document, 'visibilityState', 'get').mockReturnValue('visible');
  const checking = deferred<void>(); h.refresh.mockReturnValue(checking.promise);
  render(panel());
  fireEvent.change(await screen.findByLabelText('评论内容'), { target: { value: '只留在当前页面' } });
  fireEvent.click(within(screen.getByLabelText(`根评论 ${id(1)}`)).getByRole('button', { name: '删除评论' }));
  expect(screen.getByRole('button', { name: '确认删除' })).toBeTruthy();
  visibility.mockReturnValue('hidden'); fireEvent(document, new Event('visibilitychange'));
  expect(screen.queryByText('A 的正文1')).toBeNull(); expect(screen.queryByDisplayValue('只留在当前页面')).toBeNull();
  expect(screen.queryByRole('button', { name: '确认删除' })).toBeNull();
  expect(screen.getByText('评论已暂停，等待当前身份确认。')).toBeTruthy();
  const count = h.json.mock.calls.length;
  visibility.mockReturnValue('visible'); fireEvent(document, new Event('visibilitychange'));
  expect(screen.queryByLabelText('评论内容')).toBeNull(); expect(h.json).toHaveBeenCalledTimes(count);
  await act(async () => checking.resolve());
  expect(await screen.findByText('A 的正文1')).toBeTruthy();
  expect((screen.getByLabelText('评论内容') as HTMLTextAreaElement).value).toBe('');
  expect(h.send).not.toHaveBeenCalled();
});

it('账号切换后 A 的迟到列表、写回执与删除目标不能进入 B 的页面', async () => {
  const late = deferred<unknown>(), write = deferred<Response>();
  h.send.mockReturnValue(write.promise);
  const view = render(panel());
  fireEvent.change(await screen.findByLabelText('评论内容'), { target: { value: 'A 的草稿' } });
  fireEvent.click(screen.getByRole('button', { name: '发表评论' }));
  const writeSignal = h.send.mock.calls[0][1].signal as AbortSignal;
  h.json.mockReset().mockReturnValueOnce(late.promise);
  h.state = { phase: 'ready', epoch: h.state.epoch + 1 }; view.rerender(panel());
  expect(writeSignal.aborted).toBe(true);
  const readSignal = h.json.mock.calls[0][1].signal as AbortSignal;
  expect(screen.queryByText('A 的正文1')).toBeNull(); expect(screen.queryByDisplayValue('A 的草稿')).toBeNull();
  h.json.mockResolvedValue(listing([]));
  h.state = { phase: 'ready', epoch: h.state.epoch + 1 }; view.rerender(panel());
  expect(readSignal.aborted).toBe(true);
  expect(await screen.findByText('该版本暂无评论。')).toBeTruthy();
  await act(async () => {
    late.resolve(listing([own(2)]));
    write.resolve(json({ commentId: id(9), parentId: null, documentId: '10', versionId: '21', createdAt: '2026-10-26T00:00:00Z', deleted: false, replayed: false }, true, 201));
  });
  expect(screen.queryByText('A 的正文2')).toBeNull(); expect(screen.queryByText('已发表。')).toBeNull();
  expect(screen.queryByRole('button', { name: '删除评论' })).toBeNull(); expect(screen.queryByRole('button', { name: '删除本人评论' })).toBeNull();
  h.state = { phase: 'password', epoch: h.state.epoch + 1 }; view.rerender(panel());
  expect(screen.getByText('评论已暂停，等待当前身份确认。')).toBeTruthy(); expect(screen.queryByText('该版本暂无评论。')).toBeNull();
});
