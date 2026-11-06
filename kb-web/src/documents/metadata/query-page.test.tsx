import { beforeEach, expect, it, vi } from 'vitest';
import { act, fireEvent, render, screen, waitFor } from '@testing-library/react';
import { MemoryRouter, useLocation } from 'react-router-dom';
import { readJson } from '../../api';
import { deferred, json } from '../../test/fixtures';
import { DocumentsPage } from '../../pages/DocumentsPage';

const f = vi.hoisted(() => ({ send: vi.fn() }));
const session = { send: f.send, getSnapshot: () => ({ epoch: 1, phase: 'ready' }), json: async (path: string, init?: RequestInit) => readJson(await f.send(path, init)) };
vi.mock('../../SessionProvider', () => ({ useSession: () => ({ session, state: { phase: 'ready', epoch: 1 } }) }));
vi.mock('../../favorites/useFavorites', () => ({ useFavorites: () => ({ unavailable: () => false, status: () => ({ phase: 'ready', favorited: false }), set: vi.fn() }) }));
const page = (title: string, number = 1) => ({ page: number, size: 20, total: 40, items: [{ id: '9', spaceId: '2', title, securityLevel: 1, currentVersionNo: 1, updatedAt: '2026-11-01T00:00:00Z' }] });
beforeEach(() => f.send.mockReset().mockImplementation(async (path: string) => path === '/api/admin/spaces' ? json([]) : path === '/api/documents/query' ? json(page('初始结果')) : json({ items: [], nextCursor: null, hasMore: false })));
it('分页使用已提交条件，迟到旧页不能覆盖新查询', async () => {
  const pending = deferred<Response>();
  const store = vi.spyOn(Storage.prototype, 'setItem');
  function Location() { const location = useLocation(); return <output data-testid="location">{location.pathname + location.search + location.hash}</output>; }
  render(<MemoryRouter initialEntries={['/documents']}><DocumentsPage /><Location /></MemoryRouter>); await screen.findByRole('link', { name: '初始结果' });
  fireEvent.change(screen.getByLabelText('标题关键词'), { target: { value: '新条件%' } });
  const original = f.send.getMockImplementation()!;
  f.send.mockImplementation((path: string, init?: RequestInit) => path === '/api/documents/query'
    ? JSON.parse(String(init?.body)).page === 2 ? pending.promise : Promise.resolve(json(page('新结果'))) : original(path, init));
  fireEvent.click(screen.getByTitle('2'));
  await waitFor(() => expect(f.send.mock.calls.filter(([path]) => path === '/api/documents/query')).toHaveLength(2));
  const second = f.send.mock.calls.filter(([path]) => path === '/api/documents/query')[1];
  expect(JSON.parse(second[1].body)).toMatchObject({ page: 2, titleQuery: '' });
  fireEvent.click(screen.getByRole('button', { name: '应用筛选' })); await screen.findByRole('link', { name: '新结果' });
  await act(async () => pending.resolve(json(page('过期结果', 2)))); expect(screen.queryByRole('link', { name: '过期结果' })).toBeNull();
  const last = f.send.mock.calls.filter(([path]) => path === '/api/documents/query').at(-1)!;
  expect(JSON.parse(last[1].body)).toMatchObject({ page: 1, titleQuery: '新条件%' }); expect(last[0]).toBe('/api/documents/query');
  expect(screen.getByTestId('location').textContent).toBe('/documents'); expect(store).not.toHaveBeenCalled();
});
