import { describe, expect, it, vi } from 'vitest';
import { FileHasher, droppedFiles, fileProblem, matchFile } from './files';
import type { Capabilities } from './api';
const caps: Capabilities = { protocolVersion: 1, maxItems: 2, maxTotalBytes: 10, maxFileBytes: 5, maxManifestBytes: 1000, uploadConcurrency: 2, extensions: ['txt'] };
describe('本地导入文件', () => {
  it('显示空文件、超限和 ZIP，不展开目录或外部链接', () => {
    expect(fileProblem(new File([], 'a.txt'), caps)).toContain('为空');
    expect(fileProblem(new File(['123456'], 'a.txt'), caps)).toContain('上限');
    expect(fileProblem(new File(['x'], 'a.zip'), caps)).toContain('ZIP');
    const result = droppedFiles({ items: [{ kind: 'file', webkitGetAsEntry: () => ({ isDirectory: true }) }, { kind: 'string' }] } as unknown as DataTransfer);
    expect(result.files).toEqual([]); expect(result.rejected).toHaveLength(2);
  });
  it('摘要只启动一个 Worker，完成才调度下一项，离页终止', async () => {
    const workers: Worker[] = [];
    const factory = () => { const worker = { postMessage: vi.fn(), terminate: vi.fn(), onmessage: null, onerror: null } as unknown as Worker; workers.push(worker); return worker; };
    const hasher = new FileHasher(factory), file = new File(['hello'], 'a.txt');
    const first = hasher.hash(file, 5), second = hasher.hash(file, 5);
    await vi.waitFor(() => expect(workers).toHaveLength(1));
    workers[0]!.onmessage!({ data: { sha256: 'a'.repeat(64) } } as MessageEvent);
    expect(await first).toBe('a'.repeat(64));
    await vi.waitFor(() => expect(workers).toHaveLength(2));
    const rejected = expect(second).rejects.toMatchObject({ name: 'AbortError' }); hasher.dispose(); await rejected;
    expect(workers.every(worker => vi.mocked(worker.terminate).mock.calls.length > 0)).toBe(true);
  });
  it('同名同大小不同摘要阻止恢复', async () => {
    const hasher = { hash: vi.fn(async () => 'b'.repeat(64)) } as unknown as FileHasher;
    await expect(matchFile(new File(['hello'], 'x.txt'), { itemKey: 'one', fileName: 'x.txt', title: '标题', sizeBytes: 5, sha256: 'a'.repeat(64) }, caps, hasher)).rejects.toMatchObject({ status: 409 });
  });
});
