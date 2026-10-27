import { beforeEach, expect, it, vi } from 'vitest';
import { act, fireEvent, render, screen, waitFor } from '@testing-library/react';
import type { Session } from '../session';
import { ApiError } from '../api';
import { deferred } from '../test/fixtures';
const context = vi.hoisted(() => ({ session: {} as Session, state: { phase: 'ready', epoch: 1 } }));
vi.mock('../SessionProvider', () => ({ useSession: () => context }));
import { useFavorites } from './useFavorites';
import { FavoriteButton } from './FavoriteButton';
const resource = {};
function Buttons() {
  const favorites = useFavorites(['1', '1'], resource);
  return <><FavoriteButton id="1" favorites={favorites} /><FavoriteButton id="1" favorites={favorites} /></>;
}
beforeEach(() => { context.state = { phase: 'ready', epoch: 1 }; context.session = { post: vi.fn(), json: vi.fn() } as unknown as Session; });

it('同文档按钮共享状态，写入期间共同禁用并只发送一次', async () => {
  vi.mocked(context.session.post).mockResolvedValue({ items: [{ documentId: '1', favorited: false }] });
  const pending = deferred<unknown>(); vi.mocked(context.session.json).mockReturnValue(pending.promise);
  render(<Buttons />); const buttons = await screen.findAllByRole('button', { name: '收藏文档' });
  fireEvent.click(buttons[0]); fireEvent.click(buttons[1]); expect(context.session.json).toHaveBeenCalledOnce();
  expect(buttons.every(button => (button as HTMLButtonElement).disabled)).toBe(true);
  await act(async () => pending.resolve({ documentId: '1', favorited: true }));
  expect(screen.getAllByRole('button', { name: '取消收藏' })).toHaveLength(2); expect(context.session.post).toHaveBeenCalledOnce();
});

it('未知状态禁用写入，提供只读核对且不显示伪造未收藏', async () => {
  vi.mocked(context.session.post).mockRejectedValueOnce(new ApiError(503)).mockResolvedValue({ items: [{ documentId: '1', favorited: true }] });
  render(<Buttons />); await screen.findAllByRole('button', { name: '核对收藏状态' });
  expect(screen.queryByRole('button', { name: '收藏文档' })).toBeNull();
  fireEvent.click(screen.getAllByRole('button', { name: '核对收藏状态' })[0]);
  await screen.findAllByRole('button', { name: '取消收藏' }); expect(context.session.json).not.toHaveBeenCalled();
});

it('身份代次切换后旧结果不能填充新按钮', async () => {
  const old = deferred<unknown>(); vi.mocked(context.session.post).mockReturnValueOnce(old.promise)
    .mockResolvedValue({ items: [{ documentId: '1', favorited: false }] });
  const view = render(<Buttons />); await waitFor(() => expect(context.session.post).toHaveBeenCalledOnce());
  context.state = { phase: 'ready', epoch: 2 }; view.rerender(<Buttons />);
  await screen.findAllByRole('button', { name: '收藏文档' });
  await act(async () => old.resolve({ items: [{ documentId: '1', favorited: true }] }));
  expect(screen.queryByRole('button', { name: '取消收藏' })).toBeNull();
});
