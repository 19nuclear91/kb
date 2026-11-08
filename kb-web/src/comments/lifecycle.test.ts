import { beforeEach, expect, it, vi } from 'vitest';
import { nextTick, reactive } from 'vue';
import { flushPromises } from '@vue/test-utils';
import { fireEvent, screen, within } from '@testing-library/vue';
import { renderHost } from '../test/render';
import { ApiError } from '../api';
import { deferred, json } from '../test/fixtures';
import type { WriteBindings } from '../admin/hooks';
import CommentPanel from './CommentPanel.vue';

const h = reactive({ json: vi.fn(), send: vi.fn(), refresh: vi.fn(), state: { phase: 'ready', epoch: 1 } });
const session = { json: h.json, send: h.send, refresh: h.refresh, getSnapshot: () => h.state };
vi.mock('../SessionProvider', () => ({ useSession: () => ({ session, get state() { return h.state; } }) }));

const id = (n: number) => `dddddddd-0000-0000-0000-${String(n).padStart(12, '0')}`;
const own = (n: number) => ({ commentId: id(n), parentId: null, documentId: '10', versionId: '21', createdAt: '2026-10-26T00:00:00Z',
  deleted: false, author: { userId: '7', label: 'A 用户' }, body: `A 的正文${n}`, isOwn: true, canDelete: true });
const listing = (items: unknown[]) => ({ items, hasMore: false, nextCursor: null, canCreate: true });
const panel = () => renderHost(CommentPanel, { props: { documentId: '10', versionId: '21' } });
beforeEach(() => {
  h.state = { phase: 'ready', epoch: h.state.epoch + 1 };
  h.json.mockReset().mockResolvedValue(listing([own(1)]));
  h.send.mockReset(); h.refresh.mockReset().mockResolvedValue(undefined);
});

it('隐藏立即卸载正文、草稿与删除目标；恢复先核对身份，后台不读取', async () => {
  const visibility = vi.spyOn(document, 'visibilityState', 'get').mockReturnValue('visible');
  const checking = deferred<void>(); h.refresh.mockReturnValue(checking.promise);
  panel();
  await fireEvent.update(await screen.findByLabelText('评论内容'), '只留在当前页面');
  await fireEvent.click(within(screen.getByLabelText(`根评论 ${id(1)}`)).getByRole('button', { name: '删除评论' }));
  await nextTick(); expect(screen.getByRole('button', { name: '确认删除' })).toBeTruthy();
  visibility.mockReturnValue('hidden'); await fireEvent(document, new Event('visibilitychange'));
  expect(screen.queryByText('A 的正文1')).toBeNull(); expect(screen.queryByDisplayValue('只留在当前页面')).toBeNull();
  expect(screen.queryByRole('button', { name: '确认删除' })).toBeNull();
  expect(screen.getByText('评论已暂停，等待当前身份确认。')).toBeTruthy();
  const count = h.json.mock.calls.length;
  visibility.mockReturnValue('visible'); await fireEvent(document, new Event('visibilitychange'));
  expect(screen.queryByLabelText('评论内容')).toBeNull(); expect(h.json).toHaveBeenCalledTimes(count);
  checking.resolve(); await flushPromises();
  expect(await screen.findByText('A 的正文1')).toBeTruthy();
  expect((screen.getByLabelText('评论内容') as HTMLTextAreaElement).value).toBe('');
  expect(h.send).not.toHaveBeenCalled();
});

it('隐藏即丢弃持有者保存的未决绑定：恢复后不再显示待核对，也不沿用原标识', async () => {
  const visibility = vi.spyOn(document, 'visibilityState', 'get').mockReturnValue('visible');
  const bindings: WriteBindings = new Map();
  h.json.mockResolvedValue(listing([]));
  h.send.mockRejectedValueOnce(new ApiError(0));
  renderHost(CommentPanel, { props: { documentId: '10', versionId: '21', bindings } });
  await fireEvent.update(await screen.findByLabelText('评论内容'), '隐藏前待核对');
  await fireEvent.click(screen.getByRole('button', { name: '发表评论' }));
  expect(await screen.findByText(/提交结果尚未确认/)).toBeTruthy();
  expect(bindings.size).toBe(1);
  visibility.mockReturnValue('hidden'); await fireEvent(document, new Event('visibilitychange'));
  expect(bindings.size).toBe(0);
  visibility.mockReturnValue('visible'); await fireEvent(document, new Event('visibilitychange'));
  expect(await screen.findByLabelText('评论内容')).toBeTruthy();
  expect(screen.queryByText(/提交结果尚未确认/)).toBeNull();
  visibility.mockRestore();
});

it('账号切换后 A 的迟到列表、写回执与删除目标不能进入 B 的页面', async () => {
  const late = deferred<unknown>(), write = deferred<Response>();
  h.send.mockReturnValue(write.promise);
  panel();
  await fireEvent.update(await screen.findByLabelText('评论内容'), 'A 的草稿');
  await fireEvent.click(screen.getByRole('button', { name: '发表评论' }));
  const writeSignal = h.send.mock.calls[0][1].signal as AbortSignal;
  h.json.mockReset().mockReturnValueOnce(late.promise);
  h.state = { phase: 'ready', epoch: h.state.epoch + 1 }; await nextTick();
  expect(writeSignal.aborted).toBe(true);
  const readSignal = h.json.mock.calls[0][1].signal as AbortSignal;
  expect(screen.queryByText('A 的正文1')).toBeNull(); expect(screen.queryByDisplayValue('A 的草稿')).toBeNull();
  h.json.mockResolvedValue(listing([]));
  h.state = { phase: 'ready', epoch: h.state.epoch + 1 }; await nextTick();
  expect(readSignal.aborted).toBe(true);
  expect(await screen.findByText('该版本暂无评论。')).toBeTruthy();
  late.resolve(listing([own(2)]));
  write.resolve(json({ commentId: id(9), parentId: null, documentId: '10', versionId: '21', createdAt: '2026-10-26T00:00:00Z', deleted: false, replayed: false }, true, 201));
  await flushPromises();
  expect(screen.queryByText('A 的正文2')).toBeNull(); expect(screen.queryByText('已发表。')).toBeNull();
  expect(screen.queryByRole('button', { name: '删除评论' })).toBeNull(); expect(screen.queryByRole('button', { name: '删除本人评论' })).toBeNull();
  h.state = { phase: 'password', epoch: h.state.epoch + 1 }; await nextTick();
  expect(screen.getByText('评论已暂停，等待当前身份确认。')).toBeTruthy(); expect(screen.queryByText('该版本暂无评论。')).toBeNull();
});
