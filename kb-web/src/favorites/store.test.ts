import { expect, it, vi } from 'vitest';
import { ApiError } from '../api';
import type { Session } from '../session';
import { deferred } from '../test/fixtures';
import { FavoriteStore } from './store';

function fixture() {
  const session = { post: vi.fn(), json: vi.fn() } as unknown as Session;
  return { session, store: new FavoriteStore(session), post: vi.mocked(session.post), json: vi.mocked(session.json) };
}
const settled = async () => { await Promise.resolve(); await Promise.resolve(); await Promise.resolve(); };

it('资源批次去重，互斥写入，同向双击只发一次', async () => {
  const { store, post, json } = fixture(); post.mockResolvedValue({ items: [{ documentId: '1', favorited: false }] });
  store.bind('page', ['1', '1']); await settled(); expect(post).toHaveBeenCalledOnce();
  const pending = deferred<unknown>(); json.mockReturnValue(pending.promise);
  const first = store.set('1', true), duplicate = store.set('1', false);
  expect(store.getSnapshot()['1'].busy).toBe(true); expect(json).toHaveBeenCalledOnce();
  pending.resolve({ documentId: '1', favorited: true }); await Promise.all([first, duplicate]);
  expect(store.getSnapshot()['1']).toEqual({ phase: 'ready', favorited: true }); store.dispose();
});

it('旧状态读响应晚到不能覆盖已经完成的写入', async () => {
  const { store, post, json } = fixture(); store.bind('page', ['1'], true);
  const old = deferred<unknown>(); post.mockReturnValue(old.promise);
  const reading = store.read(['1']); json.mockResolvedValue({ documentId: '1', favorited: false });
  await store.set('1', false); old.resolve({ items: [{ documentId: '1', favorited: true }] }); await reading;
  expect(store.getSnapshot()['1']).toEqual({ phase: 'ready', favorited: false }); store.dispose();
});

it('提交结果不确定时不自动重发，主动核对只取当前状态', async () => {
  const { store, post, json } = fixture(); store.bind('page', ['1'], true);
  json.mockRejectedValue(new ApiError(0)); await store.set('1', false);
  expect(store.getSnapshot()['1'].phase).toBe('uncertain'); await store.set('1', false);
  expect(json).toHaveBeenCalledOnce(); expect(post).not.toHaveBeenCalled();
  post.mockRejectedValueOnce(new ApiError(503)); await store.read(['1']); expect(store.getSnapshot()['1'].phase).toBe('uncertain');
  post.mockResolvedValue({ items: [{ documentId: '1', favorited: true }] }); await store.read(['1']);
  expect(store.getSnapshot()['1']).toEqual({ phase: 'ready', favorited: true }); expect(json).toHaveBeenCalledOnce(); store.dispose();
});

it('成功省略目标表示不可访问，故障表示待确认', async () => {
  const { store, post } = fixture(); post.mockResolvedValue({ items: [] }); store.bind('page', ['1']); await settled();
  expect(store.getSnapshot()['1'].phase).toBe('unavailable');
  post.mockRejectedValue(new ApiError(503)); store.bind('new', ['1']); await settled();
  expect(store.getSnapshot()['1'].phase).toBe('error'); expect(store.getSnapshot()['1'].favorited).toBeUndefined(); store.dispose();
});

it('资源切换和销毁拒绝迟到读写，旧绑定状态被清空', async () => {
  const { store, post, json } = fixture(); store.bind('page', ['1'], true);
  const write = deferred<unknown>(); json.mockReturnValue(write.promise); const writing = store.set('1', false);
  store.bind('other', ['2'], true); write.resolve({ documentId: '1', favorited: false }); await writing;
  expect(store.getSnapshot()['1']).toBeUndefined(); expect(store.getSnapshot()['2'].favorited).toBe(true);
  const read = deferred<unknown>(); post.mockReturnValue(read.promise); const reading = store.read(['2']);
  const signal = post.mock.calls.at(-1)![3]!; store.dispose(); expect(signal.aborted).toBe(true);
  read.resolve({ items: [{ documentId: '2', favorited: true }] }); await reading; expect(store.getSnapshot()).toEqual({});
});

it('同资源追加已确认收藏不重置其他文档状态，不额外请求', async () => {
  const { store, post, json } = fixture(); store.bind('page', ['1'], true);
  json.mockResolvedValue({ documentId: '1', favorited: false }); await store.set('1', false);
  store.bind('page', ['1', '2'], true); expect(store.getSnapshot()['1'].favorited).toBe(false);
  expect(store.getSnapshot()['2'].favorited).toBe(true); expect(post).not.toHaveBeenCalled(); store.dispose();
});
