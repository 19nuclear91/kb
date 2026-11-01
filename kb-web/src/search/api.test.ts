import { expect, it, vi } from 'vitest';
import { encodeJson } from '../api';
import { deferred, json } from '../test/fixtures';
import type { Session } from '../session';
import { hasSearchFilters, localSearchTime, searchApi, searchBody, SearchProtocolError } from './api';

function client(response: Response) {
  const send = vi.fn().mockResolvedValue(response);
  return { send, getSnapshot: () => ({ epoch: 1 }) } as unknown as Session;
}
it('精确保留全部筛选、长整数与纳秒端点，空值不产生有效限制', () => {
  const body = searchBody('q', { spaceId: '9223372036854775807', categoryId: '9', tagIds: ['9223372036854775806', '2'], fileType: 'PDF',
    updatedFrom: '2026-11-01T00:00:00.123456788Z', updatedTo: '2026-11-01T08:00:00.123456789+08:00' });
  expect(encodeJson(body)).toContain('"spaceId":9223372036854775807');
  expect(body.tagIds).toEqual([2n, 9223372036854775806n]);
  expect(body.updatedTo).toBe('2026-11-01T08:00:00.123456789+08:00');
  expect(hasSearchFilters({ spaceId: null, categoryId: null, uncategorized: false, tagIds: [] })).toBe(false);
  expect(hasSearchFilters({ uncategorized: true })).toBe(true);
  expect(localSearchTime('2026-11-01T12:30:10.123456789')).toMatch(/^2026-11-01T12:30:10\.123456789[+-]\d\d:\d\d$/);
});
it('阻止冲突、重复 ID、非法和反向时间', () => {
  for (const filters of [{ categoryId: '1', uncategorized: true }, { tagIds: ['1', '1'] }, { spaceId: '9223372036854775808' },
    { updatedFrom: '2026-02-30T00:00:00Z' }, { updatedFrom: '2026-11-01T00:00:00' },
    { updatedFrom: '2026-11-01T00:00:00.000000002Z', updatedTo: '2026-11-01T00:00:00.000000001Z' }]) expect(() => searchBody('q', filters)).toThrow();
});
it.each([null, '2', '1, 2', '1, 1'])('版本头 %s 时丢弃正文', async version => {
  const cancel = vi.fn(), body = new ReadableStream({ cancel });
  const session = client(new Response(body, { headers: version ? { 'X-Search-Filter-Version': version } : {} }));
  await expect(searchApi.search(session, 'q', { fileType: 'PDF' })).rejects.toBeInstanceOf(SearchProtocolError);
  expect(cancel).toHaveBeenCalledOnce(); expect(session.send).toHaveBeenCalledOnce();
});
it('确认后才读数组，无筛选兼容缺头，状态错误优先', async () => {
  const response = json([]); response.headers.set('X-Search-Filter-Version', '1');
  await expect(searchApi.search(client(response), 'q', { fileType: 'PDF' })).resolves.toEqual([]);
  await expect(searchApi.search(client(json([])), 'q', {})).resolves.toEqual([]);
  await expect(searchApi.search(client(new Response(null, { status: 429 })), 'q', { fileType: 'PDF' })).rejects.toMatchObject({ status: 429 });
});
it('候选用固定 POST，404 不变成空目录，不回退端点', async () => {
  const session = client(new Response(null, { status: 404 }));
  await expect(searchApi.categories(session, '9223372036854775807', null, null)).rejects.toMatchObject({ status: 404 });
  expect(session.send).toHaveBeenCalledExactlyOnceWith('/api/search/categories', expect.objectContaining({ method: 'POST', body: '{"spaceId":9223372036854775807,"parentId":null}' }));
  const tags = client(json({ items: [{ id: 9223372036854775807n, name: '停用', enabled: false }], hasMore: false, nextCursor: null }));
  expect((await searchApi.tags(tags, ' %_ ', null)).items[0].id).toBe('9223372036854775807');
  expect(tags.send).toHaveBeenCalledWith('/api/search/tags', expect.objectContaining({ body: '{"nameQuery":"%_"}' }));
});
it('身份变更后迟到的响应不进入解码', async () => {
  const pending = deferred<Response>(); let epoch = 1;
  const session = { send: () => pending.promise, getSnapshot: () => ({ epoch }) } as unknown as Session;
  const result = searchApi.search(session, 'q', {}); epoch++;
  pending.resolve(json([])); await expect(result).rejects.toMatchObject({ name: 'AbortError' });
});
