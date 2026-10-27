import { expect, it, vi } from 'vitest';
import { ApiError } from '../api';
import { Session } from '../session';
import { deferred, json, storage } from '../test/fixtures';
import { COMMENT_MAX, CommentError, commentBody, commentDetail, commentLength, commentPaths, commentWrite, createIntent, deleteIntent,
  replyPage, rootPage } from './api';

const root = 'aaaaaaaa-0000-0000-0000-000000000001', reply = 'aaaaaaaa-0000-0000-0000-000000000002';
const raw = (over: Record<string, unknown> = {}) => ({ commentId: root, parentId: null, documentId: '10', versionId: '21',
  createdAt: '2026-10-26T00:00:00Z', deleted: false, author: { userId: '7', label: '成员7' }, body: '正文', isOwn: false, canDelete: false, ...over });
const page = (over: Record<string, unknown> = {}) => ({ items: [raw()], hasMore: false, nextCursor: null, canCreate: true, ...over });
const fake = (value: unknown) => ({ json: vi.fn().mockResolvedValue(value) }) as unknown as Session & { json: ReturnType<typeof vi.fn> };
const signal = new AbortController().signal;

it('正文只去首尾空白、按 code point 计数，拒绝 NUL 与孤立代理且不截断', () => {
  expect(commentBody('  第一行\r\n第二行\t ')).toBe('第一行\r\n第二行');
  // 与服务端一致：不换行空格不是首尾空白，不做兼容折叠。
  expect(commentBody('\u00a0x')).toBe('\u00a0x');
  expect(commentBody('e\u0301')).toBe('e\u0301');
  expect(commentBody('😀'.repeat(COMMENT_MAX))).toHaveLength(COMMENT_MAX * 2);
  expect(commentLength(' 😀😀 ')).toBe(2);
  for (const bad of ['', ' \n ', 'a\u0000b', 'x\ud800', '\udc00', 'a'.repeat(COMMENT_MAX + 1), '😀'.repeat(COMMENT_MAX) + 'a'])
    expect(() => commentBody(bad)).toThrow(ApiError);
});

it('路径只接受精确 ID 与 UUID，游标非法在请求前拒绝', () => {
  expect(commentPaths.roots('9007199254740993', '9007199254740995')).toBe('/api/documents/9007199254740993/versions/9007199254740995/comments?limit=20');
  expect(commentPaths.replies('10', '21', root.toUpperCase(), 'c_-')).toBe(`/api/documents/10/versions/21/comments/${root}/replies?limit=20&cursor=c_-`);
  for (const build of [() => commentPaths.roots('0', '21'), () => commentPaths.roots('10', '21', 'bad cursor'), () => commentPaths.detail('10', '21', '5'),
    () => commentPaths.replies('10', '21', '../admin')]) expect(build).toThrow(ApiError);
});

it('大整数 ID 经真实 Session 解码保持精确，读取不缓存、不带 Cookie', async () => {
  const body = `{"code":200,"message":"ok","data":{"items":[{"commentId":"${root}","parentId":null,"documentId":9007199254740993,`
    + `"versionId":9007199254740995,"createdAt":"2026-10-26T00:00:00Z","deleted":false,"author":{"userId":9007199254740997,"label":"甲"},`
    + `"body":"<b>x</b>","isOwn":true,"canDelete":true}],"hasMore":false,"nextCursor":null,"canCreate":false}}`;
  const transport = vi.fn(async () => new Response(body, { headers: { 'Content-Type': 'application/json' } }));
  const result = await rootPage(new Session(storage(), transport as typeof fetch), '9007199254740993', '9007199254740995', null, signal);
  expect(result.items[0]).toMatchObject({ documentId: '9007199254740993', versionId: '9007199254740995', author: { userId: '9007199254740997', label: '甲' }, body: '<b>x</b>' });
  expect(result.canCreate).toBe(false);
  expect(transport).toHaveBeenCalledWith('/api/documents/9007199254740993/versions/9007199254740995/comments?limit=20',
    expect.objectContaining({ cache: 'no-store', credentials: 'omit' }));
});

it('列表严格校验：跨版本、他人可删、墓碑带正文、父节点不符、重复与隐藏总数均为无效，不当作空讨论', async () => {
  for (const bad of [page({ items: [raw({ versionId: '22' })] }), page({ items: [raw({ canDelete: true })] }),
    page({ items: [raw({ deleted: true, body: '原文', author: null })] }), page({ items: [raw({ deleted: true, body: null, author: { userId: '7', label: 'x' } })] }),
    page({ items: [raw({ deleted: true, body: null, author: null, isOwn: true, canDelete: true })] }),
    page({ items: [raw({ parentId: reply })] }), page({ items: [raw(), raw()] }), page({ total: 3 }), page({ hasMore: true, nextCursor: null }),
    page({ items: [raw({ title: '泄露标题' })] }), page({ items: [raw({ author: { userId: '7', label: '甲', username: 'alice' } })] }),
    page({ items: [raw({ body: 'x'.repeat(COMMENT_MAX + 1) })] }), page({ items: [raw({ commentId: '5' })] }), null])
    await expect(rootPage(fake(bad), '10', '21', null, signal)).rejects.toMatchObject({ status: 503 });
  const tombstone = await rootPage(fake(page({ items: [raw({ deleted: true, body: null, author: null })] })), '10', '21', null, signal);
  expect(tombstone.items[0]).toMatchObject({ deleted: true, body: null, author: null, isOwn: false, canDelete: false });
  await expect(rootPage(fake(page()), '10', '21', 'bad cursor', signal)).rejects.toMatchObject({ status: 400 });
  const failing = { json: vi.fn().mockRejectedValue(new ApiError(404)) } as unknown as Session;
  await expect(rootPage(failing, '10', '21', null, signal)).rejects.toMatchObject({ status: 404 });
});

it('回复页只接受本根下的回复，详情只接受所请求的固定评论', async () => {
  const replies = page({ items: [raw({ commentId: reply, parentId: root })] });
  expect((await replyPage(fake(replies), '10', '21', root, null, signal)).items[0].parentId).toBe(root);
  await expect(replyPage(fake(page()), '10', '21', root, null, signal)).rejects.toMatchObject({ status: 503 });
  const detail = await commentDetail(fake({ item: raw({ commentId: reply, parentId: root }), canReply: false }), '10', '21', reply, signal);
  expect(detail).toMatchObject({ canReply: false, item: { commentId: reply, parentId: root, versionId: '21' } });
  for (const bad of [{ item: raw(), canReply: true }, { item: raw({ commentId: reply, versionId: '22' }), canReply: true }, { item: raw({ commentId: reply }) }])
    await expect(commentDetail(fake(bad), '10', '21', reply, signal)).rejects.toMatchObject({ status: 503 });
});

it('写意图只携带规范化正文与根 UUID；删除无请求体；非法目标在发送前拒绝', () => {
  expect(createIntent('10', '21', null, '  正文\n ')).toMatchObject({ method: 'POST', path: '/api/documents/10/versions/21/comments', body: { body: '正文' } });
  expect(createIntent('10', '21', root.toUpperCase(), 'x').body).toEqual({ parentId: root, body: 'x' });
  const removal = deleteIntent('10', '21', reply);
  expect(removal).toMatchObject({ method: 'DELETE', path: `/api/documents/10/versions/21/comments/${reply}` });
  expect(removal.body).toBeUndefined();
  for (const build of [() => createIntent('10', '21', null, ' '), () => createIntent('10', '21', '5', 'x'), () => deleteIntent('10', '0', reply)])
    expect(build).toThrow(ApiError);
});

function writer(response: Response | Promise<Response>, epoch = { value: 1 }) {
  const send = vi.fn().mockReturnValue(response);
  return { session: { send, getSnapshot: () => ({ epoch: epoch.value }) } as unknown as Session, send, epoch };
}
const receipt = (over: Record<string, unknown> = {}) => ({ commentId: reply, parentId: root, documentId: '10', versionId: '21',
  createdAt: '2026-10-26T00:00:00Z', deleted: false, replayed: false, ...over });

it('新建 201、匹配重放 200、删除 204；回执不一致或状态不符视为结果待核对', async () => {
  const intent = createIntent('10', '21', root, '回复');
  const created = writer(json(receipt(), true, 201));
  expect(await commentWrite(created.session, intent, 'req-1')).toMatchObject({ commentId: reply, replayed: false });
  expect(created.send).toHaveBeenCalledWith('/api/documents/10/versions/21/comments', expect.objectContaining({ method: 'POST',
    headers: { 'X-Request-Id': 'req-1', 'Content-Type': 'application/json' }, body: `{"parentId":"${root}","body":"回复"}` }));
  expect(await commentWrite(writer(json(receipt({ replayed: true, deleted: true }), true, 200)).session, intent, 'req-1'))
    .toMatchObject({ replayed: true, deleted: true });
  for (const bad of [json(receipt({ replayed: true }), true, 201), json(receipt(), true, 200), json(receipt({ parentId: null }), true, 201),
    json(receipt({ versionId: '22' }), true, 201), json(receipt({ deleted: true }), true, 201), json({ ...receipt(), body: '正文' }, true, 201),
    new Response(null, { status: 204 })])
    await expect(commentWrite(writer(bad).session, intent, 'req-1')).rejects.toMatchObject({ status: 0 });
  const removal = writer(new Response(null, { status: 204 }));
  expect(await commentWrite(removal.session, deleteIntent('10', '21', reply), 'req-2')).toBeUndefined();
  expect(removal.send.mock.calls[0][1]).toMatchObject({ method: 'DELETE', headers: { 'X-Request-Id': 'req-2' }, body: undefined });
  await expect(commentWrite(writer(json(receipt(), true, 200)).session, deleteIntent('10', '21', reply), 'req-2')).rejects.toMatchObject({ status: 0 });
  await expect(commentWrite(writer(json({})).session, { method: 'POST', path: '/api/documents/10/versions/21/comments', body: {} }, 'r'))
    .rejects.toMatchObject({ status: 400 });
});

it('失败体只映射固定错误码；状态不符或超限退回通用提示；身份切换按中止处理', async () => {
  const intent = createIntent('10', '21', null, 'x');
  const failure = (code: string, status: number) => json({ code: 500, message: '评论请求未完成', data: { errorCode: code } }, false, status);
  await expect(commentWrite(writer(failure('FORBIDDEN', 403)).session, intent, 'r')).rejects.toBeInstanceOf(CommentError);
  await expect(commentWrite(writer(failure('STATE_CONFLICT', 409)).session, intent, 'r')).rejects.toMatchObject({ status: 409, code: 'STATE_CONFLICT' });
  const mismatch: unknown = await commentWrite(writer(failure('STATE_CONFLICT', 403)).session, intent, 'r').catch((error: unknown) => error);
  expect(mismatch).not.toBeInstanceOf(CommentError); expect(mismatch).toMatchObject({ status: 403 });
  await expect(commentWrite(writer(failure('SERVICE_UNAVAILABLE', 503)).session, intent, 'r')).rejects.toMatchObject({ status: 503 });
  const pending = deferred<Response>(), epoch = { value: 1 }, stale = writer(pending.promise, epoch);
  const result = commentWrite(stale.session, intent, 'r');
  epoch.value = 2; pending.resolve(json(receipt({ parentId: null }), true, 201));
  await expect(result).rejects.toMatchObject({ name: 'AbortError' });
});
