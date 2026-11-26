import { describe, expect, it, vi } from 'vitest';
import type { Session } from '../session';
import { deferred, me } from '../test/fixtures';
import { cardApi, cardText, content } from './api';
import { envelope } from './protocol';
import { dates, detail, item, page, response, revision } from './fixtures';
import { cardRouteKind } from './routes';
import { safeReturnPath } from '../navigation';

function session(send: (path: string, options?: RequestInit) => Promise<Response>) {
  const state = { phase: 'ready', me, epoch: 1 };
  return { state, value: { getSnapshot: () => state, send } as unknown as Session };
}
describe('标准答案独立精确协议', () => {
  it('保持大整数身份、合法空页和精确旧链接', async () => {
    const send = vi.fn(async (_path: string, _options?: RequestInit) => response(page([item]))), auth = session(send);
    expect((await cardApi.browse(auth.value, {}, new AbortController().signal)).items[0]?.cardId).toBe('9007199254740993');
    send.mockResolvedValueOnce(response(page([])));
    expect((await cardApi.browse(auth.value, {}, new AbortController().signal)).items).toEqual([]);
    send.mockResolvedValueOnce(response(detail));
    expect((await cardApi.detail(auth.value, item.cardId, '21', new AbortController().signal)).revision.revisionId).toBe('21');
    expect(send.mock.calls.at(-1)?.[0]).toContain('/revisions/21');
  });
  it.each([{}, { 'X-Answer-Card-Version': '2' }, { 'Cache-Control': 'public' }] as Record<string, string>[])('缺失或损坏协议拒绝成功 %j', async headers => {
    const auth = session(async () => Object.keys(headers).length ? response(page([]), headers) : new Response(JSON.stringify({ code: 200, data: [] }), { headers: { 'Content-Type': 'application/json' } }));
    await expect(cardApi.browse(auth.value, {}, new AbortController().signal)).rejects.toMatchObject({ status: 503 });
  });
  it.each([404, 429, 503])('区分HTTP %s与空结果且不回显失败正文', async status => {
    const auth = session(async () => new Response('PRIVATE_SOURCE', { status }));
    await expect(cardApi.browse(auth.value, {}, new AbortController().signal)).rejects.toMatchObject({ status });
  });
  it('拒绝数字ID、缺字段、非法日期、旧数组和重复字段', async () => {
    for (const data of [[], page([{ ...item, cardId: 9007199254740992 }]), page([{ ...item, dates: { ...dates, effectiveFrom: '2026-02-30' } }]), page([{ ...item, revisionId: undefined }])]) {
      const auth = session(async () => response(data));
      await expect(cardApi.browse(auth.value, {}, new AbortController().signal)).rejects.toMatchObject({ status: 503 });
    }
    expect(() => envelope('{"code":200,"code":200,"message":"ok","data":null}')).toThrow();
    expect(() => envelope('{"code":2e2,"message":"ok","data":null}')).toThrow();
  });
  it('独立搜索只传问题、空间及上限，拒绝服务端更换提交条件', async () => {
    const send = vi.fn(async (_path: string, _options?: RequestInit) => response({ items: [], asOfDate: '2026-11-26', evaluatedAt: '2026-11-26T12:00:00+08:00', appliedQuery: '问题', appliedSpaceId: '9' }));
    const auth = session(send);
    await cardApi.search(auth.value, { query: ' 问题 ', spaceId: '9' }, new AbortController().signal);
    expect(send.mock.calls[0]?.[0]).toBe('/api/answer-cards/search');
    expect(JSON.parse(send.mock.calls[0]?.[1]?.body as string)).toEqual({ query: '问题', spaceId: '9', limit: 10 });
    await expect(cardApi.search(auth.value, { query: '另一个问题', spaceId: '9' }, new AbortController().signal)).rejects.toMatchObject({ status: 503 });
  });
  it('新卡片修订不能替代旧详情；换号后的迟到响应废弃', async () => {
    const late = deferred<Response>(), auth = session(() => late.promise);
    const pending = cardApi.detail(auth.value, item.cardId, '21', new AbortController().signal);
    auth.state.epoch++; late.resolve(response(detail));
    await expect(pending).rejects.toMatchObject({ name: 'AbortError' });
    const changed = session(async () => response({ ...detail, revision: { ...revision, revisionId: '22' } }));
    await expect(cardApi.detail(changed.value, item.cardId, '21', new AbortController().signal)).rejects.toMatchObject({ status: 503 });
  });
  it('草稿输入拒绝归一重复、同文档多版本和损坏Unicode', () => {
    expect(cardText('\u00a0问题\u00a0', 1, 256)).toBe('\u00a0问题\u00a0');
    expect(cardText('\u2003问题\u2003', 1, 256)).toBe('问题');
    const draft = { question: 'CAFÉ', aliases: [], answer: '', applicabilityText: '', dates: { effectiveFrom: null, untilKind: 'UNDECLARED' as const, effectiveUntil: null }, sources: [] };
    expect(content(draft).answer).toBe('');
    expect(() => content({ ...draft, aliases: ['cafe\u0301'] })).toThrow();
    expect(() => content({ ...draft, answer: '\uD800' })).toThrow();
    expect(() => content({ ...draft, sources: [{ documentId: '1', versionId: '2', applicabilityVersion: '0' }, { documentId: '1', versionId: '3', applicabilityVersion: '0' }] })).toThrow();
  });
  it('固定路由优先且登录回跳保留合法精确身份', () => {
    expect(cardRouteKind('/answer-cards/workspace')).toBe('workspaces');
    expect(cardRouteKind('/answer-cards/management')).toBe('management');
    expect(safeReturnPath(item.detailPath)).toBe(item.detailPath);
    expect(safeReturnPath('/answer-cards/01/workspace')).toBe('/documents');
    expect(safeReturnPath('/answer-cards/9223372036854775808/workspace')).toBe('/documents');
  });
});
