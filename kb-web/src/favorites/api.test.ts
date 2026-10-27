import { expect, it, vi } from 'vitest';
import { Session } from '../session';
import { json, me, storage } from '../test/fixtures';
import { favoritePage, favoriteStates, setFavorite } from './api';

async function client(respond: (path: string, init: RequestInit) => Response) {
  const transport = vi.fn(async (input: RequestInfo | URL, init?: RequestInit) => String(input) === '/api/auth/me' ? json(me) : respond(String(input), init!));
  const session = new Session(storage(), transport); await session.refresh(); transport.mockClear();
  return { session, transport };
}
const signal = () => new AbortController().signal;

it('批次去重、long 精确发送和 R 响应消费', async () => {
  const { session, transport } = await client(() => json({ items: [{ documentId: 9223372036854775807n, favorited: true }] }));
  expect(await favoriteStates(session, ['9223372036854775807', '9223372036854775807', '2'], signal()))
    .toEqual([{ documentId: '9223372036854775807', favorited: true }]);
  expect(transport.mock.calls[0][1]?.body).toBe('{"documentIds":[9223372036854775807,2]}');
  expect(transport.mock.calls[0][0]).toBe('/api/documents/favorites/status');
  expect(await favoriteStates(session, [], signal())).toEqual([]); expect(transport).toHaveBeenCalledOnce();
});

it('明确 PUT/DELETE，无请求体且不反转目标', async () => {
  const { session, transport } = await client((_, init) => json({ documentId: 9007199254740993n, favorited: init.method === 'PUT' }));
  expect((await setFavorite(session, '9007199254740993', true, signal())).favorited).toBe(true);
  expect((await setFavorite(session, '9007199254740993', false, signal())).favorited).toBe(false);
  expect(transport.mock.calls.map(call => [call[0], call[1]?.method, call[1]?.body])).toEqual([
    ['/api/documents/9007199254740993/favorite', 'PUT', undefined], ['/api/documents/9007199254740993/favorite', 'DELETE', undefined],
  ]);
});

it('状态故障、损坏响应和错误回执都不伪装未收藏', async () => {
  for (const response of [() => json({}, false, 503), () => json({ items: [{ documentId: 2, favorited: true }] }),
    () => json({ items: [{ documentId: 1, favorited: true }, { documentId: 1, favorited: false }] }), () => json({ items: [{ documentId: 1 }] })]) {
    const { session } = await client(response);
    await expect(favoriteStates(session, ['1'], signal())).rejects.toMatchObject({ status: 503 });
  }
  const { session } = await client(() => json({ documentId: 1, favorited: false }));
  await expect(setFavorite(session, '1', true, signal())).rejects.toMatchObject({ status: 503 });
});

it('列表保留当前版本空值并仅沿服务器游标续页', async () => {
  const { session, transport } = await client(() => json({ items: [{ documentId: 1, title: '标题', spaceId: 2, securityLevel: 1,
    currentVersionId: null, currentVersionNo: null, favoritedAt: '2026-10-24T00:00:00Z' }], hasMore: false, nextCursor: null }));
  expect((await favoritePage(session, 'opaque', signal())).items[0].currentVersionId).toBeNull();
  expect(transport.mock.calls[0][0]).toBe('/api/documents/favorites?limit=20&cursor=opaque');
  await expect(favoritePage(session, '../invalid', signal())).rejects.toMatchObject({ status: 400 });
});

it('拒绝非法输入与超过上限的批次，网络失败保留不确定状态', async () => {
  const { session, transport } = await client(() => { throw new Error('offline'); });
  await expect(favoriteStates(session, ['0'], signal())).rejects.toMatchObject({ status: 400 });
  await expect(favoriteStates(session, Array.from({ length: 101 }, (_, index) => String(index + 1)), signal())).rejects.toMatchObject({ status: 400 });
  expect(transport).not.toHaveBeenCalled();
  await expect(setFavorite(session, '1', true, signal())).rejects.toMatchObject({ status: 0 });
});
