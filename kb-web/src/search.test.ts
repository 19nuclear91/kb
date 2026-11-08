import { beforeEach, expect, it, vi } from 'vitest';
import { nextTick, reactive } from 'vue';
import { flushPromises } from '@vue/test-utils';
import { fireEvent, render, screen, waitFor } from '@testing-library/vue';
import { ApiError } from './api';
import { deferred, json, me } from './test/fixtures';
import { searchApi } from './search/api';
import type { Session } from './session';
import type { Hit } from './types';

const context = reactive({ session: {} as Session, state: { phase: 'ready', me: null as typeof me | null, epoch: 1 } });
vi.mock('./SessionProvider', () => ({ useSession: () => context }));
vi.mock('./favorites/useFavorites', () => ({ useFavorites: () => ({ unavailable: () => false }) }));
vi.mock('./favorites/FavoriteButton.vue', () => ({ default: { render: () => null } }));
vi.mock('./CitationDrawer.vue', () => ({ default: { render: () => null } }));
import SearchPage from './pages/SearchPage.vue';
beforeEach(() => {
  context.state = { phase: 'ready', me, epoch: 1 };
  context.session = { json: vi.fn().mockResolvedValue([]), send: vi.fn().mockImplementation(async () => json({ items: [], hasMore: false, nextCursor: null })), getSnapshot: () => context.state } as unknown as Session;
  vi.spyOn(searchApi, 'search').mockResolvedValue([]);
});
async function submit(query: string) {
  await fireEvent.update(screen.getByLabelText('搜索词'), query);
  await fireEvent.submit(screen.getByLabelText('搜索词').closest('form')!);
}
it('补充字符按 code point 接受 8000 边界且保持固定 POST 参数', async () => {
  render(SearchPage); const query = '😀'.repeat(8000); await submit(query); await screen.findByText('当前条件下未找到可用结果');
  expect(searchApi.search).toHaveBeenCalledExactlyOnceWith(context.session, query, expect.objectContaining({ spaceId: null, categoryId: null, tagIds: [] }), expect.any(AbortSignal));
});
it.each(['😀'.repeat(8001), 'x\0y', '\ud800', '\udc00'])('非法输入不发送也不截断（%#）', async query => {
  render(SearchPage); await submit(query);
  await waitFor(() => expect(screen.getByLabelText('搜索词').getAttribute('aria-invalid')).toBe('true')); expect(searchApi.search).not.toHaveBeenCalled();
});
it('允许部署收紧页面上限', async () => {
  render(SearchPage, { props: { maxQueryCodepoints: 2 } }); await submit('😀😀😀');
  await screen.findByText('搜索词最多 2 个字符，请缩短后重试。'); expect(searchApi.search).not.toHaveBeenCalled();
});
it.each([[400, '搜索输入不符合要求，请修改后重试。'], [413, '搜索请求过大，请缩短后重试。'],
  [429, '搜索请求过于频繁，请稍后重试。'], [503, '检索暂不可用，请稍后重试。']] as const)('%i 使用搜索专用提示且不自动重试', async (status, message) => {
  vi.mocked(searchApi.search).mockRejectedValue(Object.assign(new ApiError(status), { retryAfter: 1 }));
  render(SearchPage); await submit('q'); await screen.findByText(message);
  expect(screen.queryByText(/部门额度|文件超过/)).toBeNull(); expect(screen.queryByText('未找到可用结果')).toBeNull();
  expect(searchApi.search).toHaveBeenCalledOnce(); expect((screen.getByLabelText('搜索词') as HTMLInputElement).value).toBe('q');
});
it('合法降级结果保持响应次序，迟到错误及重试建议不能覆盖新结果', async () => {
  const old = deferred<Hit[]>();
  vi.mocked(searchApi.search).mockReturnValueOnce(old.promise).mockResolvedValueOnce([{ chunkId: '2', versionId: null, content: '第二原序' }, { chunkId: '1', versionId: null, content: '第一原序' }]);
  render(SearchPage); await submit('old'); await waitFor(() => expect(searchApi.search).toHaveBeenCalledOnce());
  const signal = vi.mocked(searchApi.search).mock.calls[0][3]!; await submit('new'); await screen.findByText('第二原序'); expect(signal.aborted).toBe(true);
  old.reject(Object.assign(new ApiError(429), { retryAfter: 1 })); await flushPromises();
  expect(screen.queryByText('搜索请求过于频繁，请稍后重试。')).toBeNull();
  expect(screen.getAllByText(/第[一二]原序/).map(node => node.textContent)).toEqual(['第二原序', '第一原序']); expect(searchApi.search).toHaveBeenCalledTimes(2);
});
it.each(['hidden', 'identity', 'unmount'])('%s 清理旧请求，迟到内容不可恢复', async mode => {
  const old = deferred<Hit[]>(); vi.mocked(searchApi.search).mockReturnValueOnce(old.promise);
  const view = render(SearchPage); await submit('old'); await waitFor(() => expect(searchApi.search).toHaveBeenCalledOnce());
  const signal = vi.mocked(searchApi.search).mock.calls[0][3]!;
  if (mode === 'hidden') {
    vi.spyOn(document, 'visibilityState', 'get').mockReturnValue('hidden'); await fireEvent(document, new Event('visibilitychange'));
  } else if (mode === 'identity') {
    context.state = { ...context.state, epoch: 2 }; await nextTick();
  } else {
    view.unmount();
  }
  expect(signal.aborted).toBe(true); old.resolve([{ chunkId: '1', content: '旧敏感内容' } as Hit]); await flushPromises();
  expect(screen.queryByText('旧敏感内容')).toBeNull(); expect(screen.queryByText('未找到可用结果')).toBeNull();
});
