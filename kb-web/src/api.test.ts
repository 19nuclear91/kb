import { describe, expect, it, vi } from 'vitest';
import { ApiError, decodeJson, encodeJson, filePath, positiveId, readJson } from './api';
import { readFile } from './FileAccess';
import { json } from './test/fixtures';
import type { Session } from './session';

describe('传输协议', () => {
  it('分别消费业务封装、直接 DTO 和无正文 204', async () => {
    expect(await readJson(json({ name: '文档' }))).toEqual({ name: '文档' });
    expect(await readJson(json({ title: '会话' }, false), false)).toEqual({ title: '会话' });
    expect(await readJson(new Response(null, { status: 204 }), false)).toBeUndefined();
    await expect(readJson(json({ code: 500, data: null }, false))).rejects.toMatchObject({ status: 503 });
    await expect(readJson(json({ message: '内部敏感错误' }, false, 403))).rejects.toThrow('当前操作被拒绝。');
  });
  it('小整数和完整 long ID 都转字符串，序号无损往返', () => {
    const value = decodeJson<{ id: string; userId: string; roles: string[]; lastTurnSeq: bigint; versionNo: number }>('{"id":9223372036854775807,"userId":1,"roles":[2,9223372036854775806],"lastTurnSeq":9007199254740993,"versionNo":2}');
    expect(value).toEqual({ id: '9223372036854775807', userId: '1', roles: ['2', '9223372036854775806'], lastTurnSeq: 9007199254740993n, versionNo: 2 });
    expect(encodeJson({ expectedLastTurnSeq: value.lastTurnSeq })).toBe('{"expectedLastTurnSeq":9007199254740993}');
    expect(decodeJson<{ turnSeq: bigint }>('{"turnSeq":0}').turnSeq).toBe(0n);
  });
  it.each(['0', '-1', '1.1', '9223372036854775808', '1/../2'])('拒绝非法身份 %s', value => expect(() => positiveId(value)).toThrow(ApiError));
  it.each(['https://evil.test/a', '//evil.test/a', '/api/documents/1/download?satoken=x', '/api/documents/1/../2/preview'])('拒绝非预期文件路径 %s', value => expect(() => filePath(value)).toThrow(ApiError));
  it.each([['application/pdf', '/preview'], ['text/plain', '/preview'], ['application/octet-stream', '/download']])('文件媒体类型 %s', async (type, action) => {
    const send = vi.fn().mockResolvedValue(new Response('文件内容', { headers: { 'Content-Type': type } }));
    const blob = await readFile({ send } as unknown as Session, `/api/documents/1/versions/2${action}`, new AbortController().signal);
    expect(blob.type).toBe(type);
    expect(send.mock.calls[0][0]).toBe(`/api/documents/1/versions/2${action}`);
  });
  it('JSON 错误和不支持的预览不交给 Blob 消费者', async () => {
    for (const response of [json({ private: 'error' }, false, 404), json({ private: 'error' }, false), new Response('office', { headers: { 'Content-Type': 'application/msword' } })]) {
      const blob = vi.spyOn(response, 'blob');
      await expect(readFile({ send: vi.fn().mockResolvedValue(response) } as unknown as Session, '/api/documents/1/versions/2/preview', new AbortController().signal)).rejects.toBeInstanceOf(ApiError);
      expect(blob).not.toHaveBeenCalled();
    }
  });
});
