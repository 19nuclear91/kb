import { expect, it, vi } from 'vitest';
import { ImportCreation } from './creation';
import { ImportError, type Capabilities, type Created } from './api';
import type { Session } from '../session';
const caps: Capabilities = { protocolVersion: 1, maxItems: 100, maxTotalBytes: 1024, maxFileBytes: 100, maxManifestBytes: 1048576, uploadConcurrency: 2, extensions: ['txt'] };
const manifest = { spaceId: '9', securityLevel: 0, items: [{ itemKey: 'one', fileName: 'x.txt', title: '标题', sizeBytes: 5, sha256: 'a'.repeat(64) }] };
const session = () => ({ getSnapshot: () => ({ epoch: 1, phase: 'ready' }), subscribe: () => () => {} }) as unknown as Session;
it('创建未知时不交付文件，resolve 只读，主动重试保持原 key/body', async () => {
  const api = { create: vi.fn().mockRejectedValueOnce(new ImportError(0)).mockResolvedValue({ importId: '7', replayed: true }), resolve: vi.fn(async () => null) };
  const creation = new ImportCreation(session(), api);
  await creation.begin(manifest, caps, new Map([['one', new File(['hello'], 'x.txt')]]));
  expect(creation.getSnapshot().phase).toBe('unknown'); expect(creation.takeFiles().size).toBe(0);
  await creation.resolve(); expect(api.create).toHaveBeenCalledTimes(1); await creation.retry();
  expect(api.create.mock.calls[0]![1]).toBe(api.create.mock.calls[1]![1]);
  expect(api.resolve).toHaveBeenCalledWith(expect.anything(), api.create.mock.calls[0]![1].requestId, expect.any(AbortSignal));
  expect(creation.takeFiles().size).toBe(1); expect(creation.takeFiles().size).toBe(0); creation.dispose();
});
it('重复点击只创建一次，离页清理后迟到回执不交接文件', async () => {
  let finish!: (value: Created) => void;
  const api = { create: vi.fn(() => new Promise<Created>(resolve => { finish = resolve; })), resolve: vi.fn(async () => null) };
  const creation = new ImportCreation(session(), api), files = new Map([['one', new File(['hello'], 'x.txt')]]);
  const operation = creation.begin(manifest, caps, files); await creation.begin(manifest, caps, files);
  expect(api.create).toHaveBeenCalledTimes(1); creation.dispose(); finish({ importId: '9', replayed: false }); await operation;
  expect(creation.getSnapshot().importId).toBeNull(); expect(creation.takeFiles().size).toBe(0);
});
