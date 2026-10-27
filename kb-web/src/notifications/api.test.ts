import { expect, it, vi } from 'vitest';
import { ApiError } from '../api';
import { Session } from '../session';
import { storage } from '../test/fixtures';
import { markRead, notificationPage, notificationPaths, notificationTarget, unreadCount } from './api';

const uuid = 'aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee', review = '11111111-2222-3333-4444-555555555555';
const target = { kind: 'DOCUMENT_VERSION_REVIEW', documentId: '9007199254740993', versionId: '9007199254740995', reviewId: review, commentId: null };
const item = (over = {}) => ({ notificationId: uuid, type: 'REVIEW_SUBMITTED', prompt: '服务端文本', createdAt: '2026-10-25T00:00:00Z', readAt: null, target, ...over });
const page = (over = {}) => ({ items: [item()], hasMore: false, nextCursor: null, asOf: '2026-10-25T00:00:00Z', ...over });
const fake = (value: unknown) => ({ json: vi.fn().mockResolvedValue(value) }) as unknown as Session & { json: ReturnType<typeof vi.fn> };
const signal = new AbortController().signal;

it('大整数 ID 经真实 Session 解码保持精确，请求不缓存且不带 Cookie', async () => {
  const body = `{"code":200,"message":"ok","data":{"items":[{"notificationId":"${uuid}","type":"REVIEW_APPROVED","prompt":"x",`
    + `"createdAt":"2026-10-25T00:00:00Z","readAt":null,"target":{"kind":"DOCUMENT_VERSION_REVIEW","documentId":9007199254740993,`
    + `"versionId":9223372036854775807,"reviewId":"${review}","commentId":null}}],"hasMore":false,"nextCursor":null,"asOf":"2026-10-25T00:00:00Z"}}`;
  const transport = vi.fn(async () => new Response(body, { headers: { 'Content-Type': 'application/json' } }));
  const result = await notificationPage(new Session(storage(), transport as typeof fetch), null, signal);
  expect(result.items[0].target).toEqual({ kind: 'DOCUMENT_VERSION_REVIEW', documentId: '9007199254740993',
    versionId: '9223372036854775807', reviewId: review, commentId: null });
  expect(result.items[0]).not.toHaveProperty('prompt');
  expect(transport).toHaveBeenCalledWith('/api/notifications?limit=20', expect.objectContaining({ cache: 'no-store', credentials: 'omit' }));
});

it('目标只接受白名单结构，拒绝外链、令牌和额外字段', () => {
  for (const bad of [{ ...target, kind: 'URL' }, { ...target, url: 'https://evil.test' }, { ...target, documentId: 'https://evil.test' },
    { ...target, reviewId: 'token=abc' }, { ...target, commentId: uuid }, { ...target, returnUrl: '/admin' },
    { kind: 'DOCUMENT_VERSION_REVIEW', documentId: '1', versionId: '2', reviewId: review }, null, 'x', [],
    // 评论目标只接受对外 UUID；内部数字位置或外链都不是合法目标。
    { kind: 'DOCUMENT_VERSION_COMMENT', documentId: '1', versionId: '2', reviewId: null, commentId: '3' },
    { kind: 'DOCUMENT_VERSION_COMMENT', documentId: '1', versionId: '2', reviewId: review, commentId: uuid },
    { kind: 'DOCUMENT_VERSION_COMMENT', documentId: '1', versionId: '2', reviewId: null, commentId: 'https://evil.test' }])
    expect(() => notificationTarget(bad)).toThrow(ApiError);
  expect(Object.isFrozen(notificationTarget(target))).toBe(true);
  expect(notificationTarget({ kind: 'DOCUMENT_VERSION_COMMENT', documentId: '1', versionId: '2', reviewId: null, commentId: uuid.toUpperCase() }).commentId).toBe(uuid);
});

it('列表响应严格校验：未知类型、重复、额外字段、游标与类型不一致均为无效', async () => {
  for (const bad of [page({ items: [item({ type: 'ADMIN_BROADCAST' })] }), page({ items: [item(), item()] }),
    page({ items: [item({ title: '泄露标题' })] }), page({ hasMore: true, nextCursor: null }), page({ nextCursor: 'abc' }),
    page({ items: [item({ type: 'COMMENT_REPLIED' })] }), page({ total: 9 }), page({ items: [item({ createdAt: 'later' })] })])
    await expect(notificationPage(fake(bad), null, signal)).rejects.toMatchObject({ status: 503 });
  await expect(notificationPage(fake(page()), 'bad cursor!', signal)).rejects.toMatchObject({ status: 400 });
  expect(notificationPaths.list('abc_-')).toBe('/api/notifications?limit=20&cursor=abc_-');
});

it('未读数只接受精确十进制字符串', async () => {
  const asOf = '2026-10-25T00:00:00Z';
  expect((await unreadCount(fake({ count: '9223372036854775807', asOf }), signal)).count).toBe('9223372036854775807');
  for (const bad of [{ count: 3, asOf }, { count: '03', asOf }, { count: '9223372036854775808', asOf }, { count: '-1', asOf },
    { count: '1', asOf: 'x' }, { count: '1', asOf, pending: 2 }])
    await expect(unreadCount(fake(bad), signal)).rejects.toMatchObject({ status: 503 });
});

it('已读使用固定无正文 PUT，回执不一致视为待核对，非法 ID 不发送', async () => {
  const session = fake({ notificationId: uuid.toUpperCase(), readAt: '2026-10-25T01:00:00Z' });
  expect(await markRead(session, uuid, signal)).toEqual({ notificationId: uuid, readAt: '2026-10-25T01:00:00Z' });
  expect(session.json).toHaveBeenCalledWith(`/api/notifications/${uuid}/read`, { method: 'PUT', signal });
  await expect(markRead(fake({ notificationId: review, readAt: '2026-10-25T01:00:00Z' }), uuid, signal)).rejects.toMatchObject({ status: 0 });
  const unused = fake({});
  await expect(markRead(unused, '../admin', signal)).rejects.toMatchObject({ status: 400 });
  expect(unused.json).not.toHaveBeenCalled();
});
