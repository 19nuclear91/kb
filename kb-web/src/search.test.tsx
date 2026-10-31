import { beforeEach, expect, it, vi } from 'vitest';
import { act, fireEvent, render, screen, waitFor } from '@testing-library/react';
import { ApiError } from './api';
import { deferred, me } from './test/fixtures';
import type { Session } from './session';
import type { Hit } from './types';

const context = vi.hoisted(() => ({ session: {} as Session, state: { phase: 'ready', me: null as typeof me | null, epoch: 1 } }));
vi.mock('./SessionProvider', () => ({ useSession: () => context }));
vi.mock('./favorites/useFavorites', () => ({ useFavorites: () => ({ unavailable: () => false }) }));
vi.mock('./favorites/FavoriteButton', () => ({ FavoriteButton: () => null }));
vi.mock('./CitationDrawer', () => ({ CitationDrawer: () => null }));
import { SearchPage } from './pages/Search';
beforeEach(() => {
  context.state = { phase: 'ready', me, epoch: 1 };
  context.session = { post: vi.fn().mockResolvedValue([]), getSnapshot: () => context.state } as unknown as Session;
});
function submit(query: string) {
  fireEvent.change(screen.getByLabelText('搜索词'), { target: { value: query } });
  fireEvent.submit(screen.getByLabelText('搜索词').closest('form')!);
}
it('补充字符按 code point 接受 8000 边界且保持固定 POST 参数', async () => {
  render(<SearchPage />); const query = '😀'.repeat(8000); submit(query);
  await screen.findByText('未找到可用结果');
  expect(context.session.post).toHaveBeenCalledExactlyOnceWith('/api/search', { query, candidates: 50, topK: 20 }, true, expect.any(AbortSignal));
});
it.each(['😀'.repeat(8001), 'x\0y', '\ud800', '\udc00'])('非法输入不发送也不截断（%#）', async query => {
  render(<SearchPage />); submit(query);
  await waitFor(() => expect(screen.getByLabelText('搜索词').getAttribute('aria-invalid')).toBe('true'));
  expect(context.session.post).not.toHaveBeenCalled();
});
it('允许部署收紧页面上限', async () => {
  render(<SearchPage maxQueryCodepoints={2} />); submit('😀😀😀');
  await screen.findByText('搜索词最多 2 个字符，请缩短后重试。'); expect(context.session.post).not.toHaveBeenCalled();
});
it.each([[400, '搜索输入不符合要求，请修改后重试。'], [413, '搜索请求过大，请缩短后重试。'],
  [429, '搜索请求过于频繁，请稍后重试。'], [503, '检索暂不可用，请稍后重试。']] as const)('%i 使用搜索专用提示且不自动重试', async (status, message) => {
  vi.mocked(context.session.post).mockRejectedValue(Object.assign(new ApiError(status), { retryAfter: 1 }));
  render(<SearchPage />); submit('q'); await screen.findByText(message);
  expect(screen.queryByText(/部门额度|文件超过/)).toBeNull(); expect(screen.queryByText('未找到可用结果')).toBeNull();
  expect(context.session.post).toHaveBeenCalledOnce(); expect((screen.getByLabelText('搜索词') as HTMLInputElement).value).toBe('q');
});
it('合法降级结果保持响应次序，迟到错误及重试建议不能覆盖新结果', async () => {
  const old = deferred<Hit[]>();
  vi.mocked(context.session.post).mockReturnValueOnce(old.promise).mockResolvedValueOnce([{ chunkId: '2', content: '第二原序' }, { chunkId: '1', content: '第一原序' }]);
  render(<SearchPage />); submit('old'); await waitFor(() => expect(context.session.post).toHaveBeenCalledOnce());
  const signal = vi.mocked(context.session.post).mock.calls[0][3]!;
  submit('new'); await screen.findByText('第二原序');
  expect(signal.aborted).toBe(true);
  await act(async () => old.reject(Object.assign(new ApiError(429), { retryAfter: 1 })));
  expect(screen.queryByText('搜索请求过于频繁，请稍后重试。')).toBeNull();
  expect(screen.getAllByText(/第[一二]原序/).map(node => node.textContent)).toEqual(['第二原序', '第一原序']);
  expect(context.session.post).toHaveBeenCalledTimes(2);
});
it.each(['hidden', 'identity', 'unmount'])('%s 清理旧请求，迟到内容不可恢复', async mode => {
  const old = deferred<Hit[]>(); vi.mocked(context.session.post).mockReturnValueOnce(old.promise);
  const view = render(<SearchPage />); submit('old'); await waitFor(() => expect(context.session.post).toHaveBeenCalledOnce());
  const signal = vi.mocked(context.session.post).mock.calls[0][3]!;
  if (mode === 'hidden') { vi.spyOn(document, 'visibilityState', 'get').mockReturnValue('hidden'); fireEvent(document, new Event('visibilitychange')); }
  else if (mode === 'identity') { context.state = { ...context.state, epoch: 2 }; view.rerender(<SearchPage />); }
  else view.unmount();
  expect(signal.aborted).toBe(true);
  await act(async () => old.resolve([{ chunkId: '1', content: '旧敏感内容' } as Hit]));
  expect(screen.queryByText('旧敏感内容')).toBeNull(); expect(screen.queryByText('未找到可用结果')).toBeNull();
});
