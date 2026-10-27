import { beforeEach, expect, it, vi } from 'vitest';
import { act, fireEvent, render, screen, waitFor } from '@testing-library/react';
import { MemoryRouter } from 'react-router-dom';
import type { Session } from '../session';
import { ApiError } from '../api';
import { deferred } from '../test/fixtures';
import { navigation, safeReturnPath } from '../navigation';
const context = vi.hoisted(() => ({ session: {} as Session, state: { phase: 'ready', epoch: 1 } }));
vi.mock('../SessionProvider', () => ({ useSession: () => context }));
import { FavoritesPage } from '../pages/Favorites';
beforeEach(() => { context.state = { phase: 'ready', epoch: 1 }; context.session = { json: vi.fn(), post: vi.fn() } as unknown as Session; });
const item = (id: string, title = `文档${id}`) => ({ documentId: id, title, spaceId: '3', securityLevel: 1,
  currentVersionId: null, currentVersionNo: null, favoritedAt: '2026-10-24T00:00:00Z' });
const view = () => <MemoryRouter><FavoritesPage /></MemoryRouter>;

it('收藏页使用服务器游标和创建顺序，取消后移除条目且不额外读取状态', async () => {
  vi.mocked(context.session.json).mockImplementation(async (path, init) => init?.method === 'DELETE' ? { documentId: '9', favorited: false }
    : path.includes('cursor=') ? { items: [item('4')], hasMore: false, nextCursor: null }
      : { items: [item('9')], hasMore: true, nextCursor: 'next' } as never);
  render(view()); await screen.findByRole('link', { name: '文档9' }); await screen.findByRole('button', { name: '取消收藏' });
  expect(screen.getByText('尚未发布')).toBeTruthy(); fireEvent.click(screen.getByRole('button', { name: '更多收藏' }));
  await screen.findByRole('link', { name: '文档4' }); expect(screen.getAllByRole('link').map(link => link.textContent)).toEqual(['文档9', '文档4']);
  expect(screen.getByRole('link', { name: '文档4' }).getAttribute('href')).toBe('/documents/4');
  fireEvent.click(screen.getAllByRole('button', { name: '取消收藏' })[0]);
  await waitFor(() => expect(screen.queryByText('文档9')).toBeNull()); expect(context.session.post).not.toHaveBeenCalled();
  expect(vi.mocked(context.session.json).mock.calls.some(call => call[0] === '/api/documents/favorites?limit=20&cursor=next')).toBe(true);
});

it('列表故障与空集合明确区分，失败可以主动重试', async () => {
  vi.mocked(context.session.json).mockRejectedValueOnce(new ApiError(503)).mockResolvedValue({ items: [], hasMore: false, nextCursor: null });
  render(view()); await screen.findByText('服务暂不可用，请稍后重试。'); expect(screen.queryByText('暂无可查看的收藏')).toBeNull();
  fireEvent.click(screen.getByRole('button', { name: /重\s*试/ })); await screen.findByText('暂无可查看的收藏');
});

it.each(['anonymous', 'password', 'loading', 'unavailable'])('身份 %s 不预取收藏', phase => {
  context.state.phase = phase; render(view()); expect(context.session.json).not.toHaveBeenCalled();
});

it('收藏是独立合法站内入口，拒绝夹带查询或外部返回地址', () => {
  expect(navigation.some(link => link.path === '/favorites')).toBe(true);
  expect(safeReturnPath('/favorites')).toBe('/favorites');
  expect(safeReturnPath('/favorites?userId=7')).toBe('/documents');
  expect(safeReturnPath('https://evil.example/favorites')).toBe('/documents');
});

it('页内取消结果不确定时不移除，核对当前未收藏后才移除', async () => {
  vi.mocked(context.session.json).mockImplementation(async (_, init) => {
    if (init?.method === 'DELETE') throw new ApiError(0);
    return { items: [item('1')], hasMore: false, nextCursor: null } as never;
  });
  vi.mocked(context.session.post).mockResolvedValue({ items: [{ documentId: '1', favorited: false }] });
  render(view()); fireEvent.click(await screen.findByRole('button', { name: '取消收藏' }));
  await screen.findByText('提交结果尚未确认，请核对当前收藏状态。'); expect(screen.getByText('文档1')).toBeTruthy();
  fireEvent.click(screen.getByRole('button', { name: '核对收藏状态' })); await screen.findByText('暂无可查看的收藏');
});

it('加载更多互斥，离开后取消活动请求', async () => {
  const pending = deferred<unknown>(); vi.mocked(context.session.json).mockResolvedValueOnce({ items: [item('1')], hasMore: true, nextCursor: 'next' }).mockReturnValue(pending.promise);
  const rendered = render(view()); const more = await screen.findByRole('button', { name: '更多收藏' });
  fireEvent.click(more); fireEvent.click(more); expect(context.session.json).toHaveBeenCalledTimes(2);
  const signal = vi.mocked(context.session.json).mock.calls[1][1]!.signal!;
  rendered.unmount(); expect(signal.aborted).toBe(true); await act(async () => pending.resolve({ items: [item('2')], hasMore: false, nextCursor: null }));
});

it('续页失败保留已有条目与原游标，重试互斥且成功后采用真实末页', async () => {
  const pending = deferred<unknown>();
  vi.mocked(context.session.json).mockResolvedValueOnce({ items: [item('9')], hasMore: true, nextCursor: 'next' })
    .mockRejectedValueOnce(new ApiError(503)).mockReturnValueOnce(pending.promise);
  render(view()); fireEvent.click(await screen.findByRole('button', { name: '更多收藏' }));
  await screen.findByText('服务暂不可用，请稍后重试。');
  expect(screen.getAllByRole('link').map(link => link.textContent)).toEqual(['文档9']);
  expect(screen.queryByText('暂无可查看的收藏')).toBeNull();
  const retry = screen.getByRole('button', { name: '更多收藏' });
  await waitFor(() => expect((retry as HTMLButtonElement).disabled).toBe(false));
  fireEvent.click(retry); fireEvent.click(retry);
  expect(context.session.json).toHaveBeenCalledTimes(3);
  expect((retry as HTMLButtonElement).disabled).toBe(true);
  expect(screen.queryByText('服务暂不可用，请稍后重试。')).toBeNull();
  expect(screen.getByRole('link', { name: '文档9' })).toBeTruthy();
  await act(async () => pending.resolve({ items: [item('4')], hasMore: false, nextCursor: null }));
  await screen.findByRole('link', { name: '文档4' });
  expect(screen.getAllByRole('link').map(link => link.textContent)).toEqual(['文档9', '文档4']);
  expect(screen.queryByRole('button', { name: '更多收藏' })).toBeNull();
  expect(vi.mocked(context.session.json).mock.calls.map(call => call[0])).toEqual([
    '/api/documents/favorites?limit=20', '/api/documents/favorites?limit=20&cursor=next', '/api/documents/favorites?limit=20&cursor=next',
  ]);
  expect(context.session.post).not.toHaveBeenCalled();
});
