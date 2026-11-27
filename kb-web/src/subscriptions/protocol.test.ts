import { expect, it, vi } from 'vitest';
import { ApiError } from '../api';
import { safeReturnPath } from '../navigation';
import { notificationTarget, notificationPage } from '../notifications/api';
import { cancelIntent, detail as readDetail, envelope, lookup, subscribeIntent, verifyReceipt, write } from './api';
import * as p from './protocol';
import { at, detail, generation, id, page, ready, receipt, response, target } from './testSupport';

it('拒绝重复JSON键、额外字段、损失精度的ID、非法Unicode和跨用途目标', () => {
  for (const body of ['{"code":200,"code":200,"message":"ok","data":null}', '{"code":200,"message":"ok","data":{"id":1,"id":1}}']) expect(() => envelope(body)).toThrow(ApiError);
  expect(() => p.candidate({ ...target, targetId: 9007199254740992 })).toThrow(ApiError);
  expect(() => p.candidate({ ...target, label: '\ud800' })).toThrow(ApiError);
  expect(() => p.detail({ ...detail, extra: true })).toThrow(ApiError);
  expect(() => p.detail({ ...detail, comparisonStatus: 'BASE_UNAVAILABLE' })).toThrow(ApiError);
  expect(p.candidate(target).targetId).toBe('9007199254740993');
  expect(p.detail(detail).baseVersionNo).toBe(1);
  expect(() => notificationTarget({ kind: 'KNOWLEDGE_UPDATE', updateId: id, digestId: id })).toThrow(ApiError);
  expect(notificationTarget({ kind: 'KNOWLEDGE_DIGEST', digestId: id })).toEqual({ kind: 'KNOWLEDGE_DIGEST', digestId: id });
});
it('原命令重试保持请求ID、精确版本和载荷，UNKNOWN不等于失败', async () => {
  const original = subscribeIntent(target), seen: RequestInit[] = [];
  const { session } = await ready((path, options) => { seen.push(options); return response(path.endsWith('/query')
    ? { requestId: original.requestId, status: 'UNKNOWN', receipt: null } : receipt(original.requestId)); });
  const signal = new AbortController().signal;
  await write(session, original, signal); await write(session, original, signal);
  expect(seen[0]?.body).toBe(seen[1]?.body);
  expect(new Headers(seen[0]?.headers).get('X-Request-Id')).toBe(new Headers(seen[1]?.headers).get('X-Request-Id'));
  expect(JSON.parse(String(seen[0]?.body))).toEqual({ kind: 'DOCUMENT', targetId: '9007199254740993', expectedVersion: '0' });
  expect((await lookup(session, original, signal)).status).toBe('UNKNOWN');
  expect(() => verifyReceipt({ ...receipt(original.requestId), version: '2' }, original)).toThrow(ApiError);
  const cancel = cancelIntent({ subscriptionId: id, generationId: generation, version: '1' });
  expect(() => verifyReceipt({ ...receipt(cancel.requestId), operation: 'CANCEL', version: '2', state: 'CANCELLED', generationId: id }, cancel)).toThrow(ApiError);
});
it('通知新契约同时读取新旧类型，非法条目整页失败', async () => {
  const item = { notificationId: id, type: 'KNOWLEDGE_WEEKLY_DIGEST', prompt: '固定提示', createdAt: at, readAt: null, target: { kind: 'KNOWLEDGE_DIGEST', digestId: id } };
  let invalid = false;
  const { session, transport } = await ready(() => response(page([{ ...item, target: invalid ? { ...item.target, secret: 'x' } : item.target }])));
  expect((await notificationPage(session, null, new AbortController().signal)).items).toHaveLength(1);
  expect(new Headers(transport.mock.calls[0]?.[1]?.headers).get('X-Kb-Notification-Contract')).toBe('KNOWLEDGE_SUBSCRIPTIONS_V1');
  invalid = true; await expect(notificationPage(session, null, new AbortController().signal)).rejects.toMatchObject({ status: 503 });
});
it('安全返回路径只接受知识页面固定路由，日期只接受真实周一', () => {
  expect(safeReturnPath(`/updates/${id}`)).toBe(`/updates/${id}`);
  expect(safeReturnPath('/subscriptions/documents/9007199254740993')).toBe('/subscriptions/documents/9007199254740993');
  for (const value of ['/updates/bad', `/digests/${id}?x=1`, '//example.test', '/subscriptions/documents/9223372036854775808']) expect(safeReturnPath(value)).toBe('/documents');
  expect(p.week('2026-11-23')).toBe('2026-11-23');
  for (const value of ['2026-11-24', '2026-02-30', '2026-1-5']) expect(() => p.week(value)).toThrow(ApiError);
});
it('响应体停滞也会超时并取消流，读取故障不成为空详情', async () => {
  const cancel = vi.fn();
  const { session } = await ready(() => new Response(new ReadableStream<Uint8Array>({
    start(controller) { controller.enqueue(new TextEncoder().encode('{"code":200,')); }, cancel,
  }), { headers: { 'Content-Type': 'application/json' } }));
  vi.useFakeTimers({ toFake: ['setTimeout', 'clearTimeout'] });
  try {
    const result = expect(readDetail(session, id, new AbortController().signal)).rejects.toMatchObject({ status: 503 });
    await vi.advanceTimersByTimeAsync(15000); await result;
    expect(cancel).toHaveBeenCalledOnce();
  } finally { vi.useRealTimers(); }
});
