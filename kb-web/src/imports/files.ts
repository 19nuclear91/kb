import { fileName, ImportError, type Capabilities, type FileIntent } from './api';

export function fileProblem(file: File, capabilities: Capabilities): string {
  try { fileName(file.name); } catch { return '文件名无效或过长'; }
  if (file.webkitRelativePath) return '不支持目录导入';
  if (file.size <= 0) return '文件为空';
  if (!Number.isSafeInteger(file.size) || file.size > capabilities.maxFileBytes) return '文件超过单文件上限';
  if (!capabilities.extensions.includes(fileName(file.name).split('.').at(-1)?.toLowerCase() ?? '')) return '文件格式不受支持（不展开 ZIP）';
  return '';
}
export function droppedFiles(data: DataTransfer): { files: File[]; rejected: string[] } {
  const files: File[] = [], rejected: string[] = [];
  if (!data.items?.length) return { files: Array.from(data.files), rejected: [] };
  for (const item of Array.from(data.items)) {
    if (item.kind !== 'file') { rejected.push('不支持外部链接或文本来源'); continue; }
    if (item.webkitGetAsEntry?.()?.isDirectory) { rejected.push('不支持目录导入'); continue; }
    const file = item.getAsFile();
    if (file) files.push(file); else rejected.push('无法读取所选文件');
  }
  return { files, rejected };
}
type HashWorker = Pick<Worker, 'postMessage' | 'terminate' | 'onmessage' | 'onerror'>;
export class FileHasher {
  private tail: Promise<unknown> = Promise.resolve();
  private closed = new AbortController();
  constructor(private factory: () => HashWorker = () => new Worker(new URL('./hash.worker.ts', import.meta.url), { type: 'module' })) {}
  hash(file: File, maxBytes: number, signal?: AbortSignal): Promise<string> {
    const combined = signal ? AbortSignal.any([signal, this.closed.signal]) : this.closed.signal;
    const task = this.tail.catch(() => {}).then(() => {
      if (combined.aborted) throw new DOMException('摘要已停止', 'AbortError');
      if (file.size < 1 || file.size > maxBytes) throw new ImportError(413);
      return new Promise<string>((resolve, reject) => {
        const worker = this.factory();
        const cleanup = () => { worker.terminate(); combined.removeEventListener('abort', stop); };
        const stop = () => { cleanup(); reject(new DOMException('摘要已停止', 'AbortError')); };
        combined.addEventListener('abort', stop, { once: true });
        worker.onmessage = event => { cleanup(); const value = event.data?.sha256;
          if (combined.aborted) reject(new DOMException('摘要已停止', 'AbortError'));
          else if (typeof value !== 'string' || !/^[a-f0-9]{64}$/.test(value)) reject(new ImportError(503)); else resolve(value); };
        worker.onerror = () => { cleanup(); reject(new ImportError(503)); };
        try { worker.postMessage({ file, maxBytes }); } catch { cleanup(); reject(new ImportError(503)); }
      });
    });
    this.tail = task; return task;
  }
  dispose() { this.closed.abort(); }
}
export async function matchFile(file: File, expected: FileIntent, capabilities: Capabilities, hasher: FileHasher, signal?: AbortSignal): Promise<void> {
  if (fileProblem(file, capabilities) || fileName(file.name) !== expected.fileName || file.size !== expected.sizeBytes) throw new ImportError(409);
  if (await hasher.hash(file, capabilities.maxFileBytes, signal) !== expected.sha256) throw new ImportError(409);
}
