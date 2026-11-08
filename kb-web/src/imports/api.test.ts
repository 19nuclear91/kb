import { describe, expect, it, vi } from 'vitest';
import { decodeJson } from '../api';
import type { Session } from '../session';
import { createIntent, decodeBatch, decodeItem, decodeReceipt, importApi, ImportError } from './api';
import type { Capabilities } from './api';

export const capabilities: Capabilities = { protocolVersion: 1, maxItems: 100, maxTotalBytes: 1024, maxFileBytes: 100, maxManifestBytes: 1048576, uploadConcurrency: 2, extensions: ['txt', 'pdf'] };
export const fileIntent = { itemKey: 'one', fileName: 'x.txt', title: '标题', sizeBytes: 5, sha256: 'a'.repeat(64) };
const session = (data: unknown, status = 200) => ({ getSnapshot: () => ({ epoch: 1 }), send: vi.fn(async () => new Response(JSON.stringify({ code: 200, data }), { status, headers: { 'Content-Type': 'application/json' } })) }) as unknown as Session;
describe('导入协议', () => {
  it('精确保留 Long.MAX_VALUE 身份及 revision', () => {
    const item = decodeItem(decodeJson('{"itemId":9223372036854775807,"importId":9,"ordinal":1,"revision":9223372036854775807,"state":"ACCEPTED","canUpload":false,"receipt":{"documentId":10,"versionId":20,"parseTaskId":30}}'));
    expect(item.itemId).toBe('9223372036854775807'); expect(item.revision).toBe(9223372036854775807n);
    expect(() => decodeItem({ ...item, revision: Number.MAX_SAFE_INTEGER + 1 })).toThrow();
    expect(() => decodeItem({ ...item, state: 'FAILED' })).toThrow();
    expect(() => decodeReceipt({ documentId: '1', versionId: '2' })).toThrow();
  });
  it('冻结请求体且新意图使用新标识，同内容不同项不合并', () => {
    const manifest = { spaceId: '9223372036854775807', securityLevel: 8, items: [fileIntent, { ...fileIntent, itemKey: 'two' }] };
    const intent = createIntent(manifest, capabilities); manifest.items[0] = { ...fileIntent, title: '改动' };
    expect(intent.body).toContain('9223372036854775807'); expect(intent.body).not.toContain('改动');
    expect(createIntent(manifest, capabilities).requestId).not.toBe(intent.requestId);
    expect(() => createIntent({ ...manifest, items: [fileIntent, fileIntent] }, capabilities)).toThrow();
  });
  it('拒绝损坏创建回执及不一致计数', async () => {
    const intent = createIntent({ spaceId: '9', securityLevel: 0, items: [fileIntent] }, capabilities);
    await expect(importApi.create(session({ importId: '9' }), intent)).rejects.toThrow(ImportError);
    expect(() => decodeBatch({ importId: '9', createdAt: '2026-11-08T00:00:00Z', state: 'COMPLETED', canContinue: false,
      counts: { pending: 1, processing: 0, accepted: 0, failed: 0 } })).toThrow();
  });
  it('上传使用精确 revision 与唯一 file，保留真实错误状态和上传文案', async () => {
    const s = session({ documentId: '1', versionId: '2', parseTaskId: '3' });
    const item = decodeItem({ itemId: '2', importId: '9', ordinal: 1, revision: 9007199254740993n, state: 'PENDING', canUpload: true, file: fileIntent });
    await importApi.upload(s, item, new File(['hello'], 'x.txt'), 'request');
    const options = vi.mocked(s.send).mock.calls[0]![1]!;
    expect((options.body as FormData).get('expectedRevision')).toBe('9007199254740993');
    expect([...(options.body as FormData).keys()]).toEqual(['file', 'expectedRevision']);
    for (const status of [401, 403, 409, 413, 415, 429, 503]) {
      await expect(importApi.capabilities(session(null, status))).rejects.toMatchObject({ status });
    }
    expect(new ImportError(429).message).toContain('上传容量'); expect(new ImportError(429).message).not.toContain('部门');
  });
});
