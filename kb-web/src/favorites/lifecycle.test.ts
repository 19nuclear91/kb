import { beforeEach, expect, it, vi } from 'vitest';
import { h, nextTick, reactive } from 'vue';
import { flushPromises } from '@vue/test-utils';
import { fireEvent, screen, waitFor } from '@testing-library/vue';
import { mountRouter } from '../test/app';
import type { Session } from '../session';
import { deferred } from '../test/fixtures';
const context = reactive({ session: {} as Session, state: { phase: 'ready', epoch: 1 } });
vi.mock('../SessionProvider', () => ({ useSession: () => context }));
import FavoritesPage from '../pages/FavoritesPage.vue';
const item = (id: string, title: string) => ({ documentId: id, title, spaceId: '3', securityLevel: 1,
  currentVersionId: null, currentVersionNo: null, favoritedAt: '2026-10-24T00:00:00Z' });
const page = (id: string, title: string, next: string | null = null) => ({ items: [item(id, title)], hasMore: !!next, nextCursor: next });
const show = () => mountRouter(() => h(FavoritesPage));
beforeEach(() => { context.state = { phase: 'ready', epoch: 1 }; context.session = { json: vi.fn(), post: vi.fn() } as unknown as Session; });

it('后台身份重核期间遮蔽旧内容，恢复后采用新列表并拒绝迟到页', async () => {
  const oldPage = deferred<unknown>(); vi.mocked(context.session.json).mockResolvedValueOnce(page('1', '旧内容', 'next'))
    .mockReturnValueOnce(oldPage.promise).mockResolvedValue(page('2', '当前内容'));
  show(); await fireEvent.click(await screen.findByRole('button', { name: '更多收藏' }));
  context.state = { phase: 'loading', epoch: 2 }; await nextTick(); expect(screen.queryByText('旧内容')).toBeNull();
  context.state = { phase: 'ready', epoch: 2 }; await nextTick(); await screen.findByRole('link', { name: '当前内容' });
  oldPage.resolve(page('3', '迟到内容')); await flushPromises();
  expect(screen.queryByText('迟到内容')).toBeNull(); expect(screen.queryByText('旧内容')).toBeNull();
  expect(screen.getByRole('link', { name: '当前内容' })).toBeTruthy();
});

it('退出后的旧写回不影响另一身份，活动写入信号被中止', async () => {
  const writing = deferred<unknown>(); vi.mocked(context.session.json).mockResolvedValueOnce(page('1', '用户A内容'))
    .mockReturnValueOnce(writing.promise).mockResolvedValue(page('1', '用户B内容'));
  show(); await fireEvent.click(await screen.findByRole('button', { name: '取消收藏' }));
  const signal = vi.mocked(context.session.json).mock.calls[1][1]!.signal!;
  context.state = { phase: 'anonymous', epoch: 2 }; await nextTick(); expect(signal.aborted).toBe(true);
  expect(screen.queryByText('用户A内容')).toBeNull();
  context.state = { phase: 'ready', epoch: 3 }; await nextTick(); await screen.findByRole('link', { name: '用户B内容' });
  writing.resolve({ documentId: '1', favorited: false }); await flushPromises();
  expect(screen.getByRole('link', { name: '用户B内容' })).toBeTruthy();
  expect(screen.getByRole('button', { name: '取消收藏' }).getAttribute('aria-pressed')).toBe('true');
});

it('刷新使旧续页失效，撤权后的内容不会被重新追加', async () => {
  const oldPage = deferred<unknown>(); vi.mocked(context.session.json).mockResolvedValueOnce(page('1', '已撤权', 'next'))
    .mockReturnValueOnce(oldPage.promise).mockResolvedValue({ items: [], hasMore: false, nextCursor: null });
  show(); await fireEvent.click(await screen.findByRole('button', { name: '更多收藏' }));
  await fireEvent.click(screen.getByRole('button', { name: '刷新收藏' })); await screen.findByText('暂无可查看的收藏');
  oldPage.resolve(page('2', '过期续页')); await flushPromises();
  expect(screen.queryByText('过期续页')).toBeNull(); expect(screen.queryByText('已撤权')).toBeNull();
});

it('标题按文本呈现，长整数跳转精确且无浏览器内容持久化', async () => {
  const title = '<img src="https://example.invalid/secret" onerror="alert(1)">';
  vi.mocked(context.session.json).mockResolvedValue(page('9223372036854775807', title));
  const storage = vi.spyOn(Storage.prototype, 'setItem'); show();
  const link = await screen.findByRole('link', { name: title });
  expect(link.getAttribute('href')).toBe('/documents/9223372036854775807');
  expect(document.querySelector('img[src="https://example.invalid/secret"]')).toBeNull();
  await waitFor(() => expect(storage).not.toHaveBeenCalled());
});
