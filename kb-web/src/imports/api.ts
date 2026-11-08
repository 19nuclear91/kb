import { ApiError, encodeJson, positiveId, readJson } from '../api';
import type { Session } from '../session';

export interface FileIntent { itemKey: string; fileName: string; title: string; sizeBytes: number; sha256: string }
export interface Manifest { spaceId: string; securityLevel: number; items: FileIntent[] }
export interface CreateIntent { requestId: string; body: string }
export interface Receipt { documentId: string; versionId: string; parseTaskId: string }
export interface Created { importId: string; replayed: boolean }
export interface Page<T> { items: T[]; hasMore: boolean; nextCursor: string | null }
export interface Capabilities { protocolVersion: 1; maxItems: number; maxTotalBytes: number; maxFileBytes: number; maxManifestBytes: number; uploadConcurrency: number; extensions: string[] }
export type ItemState = 'PENDING' | 'PROCESSING' | 'ACCEPTED' | 'FAILED';
export type FailureCode = 'INVALID_FILE' | 'PAYLOAD_TOO_LARGE' | 'STORAGE_UNAVAILABLE' | 'EXPIRED' | 'ACCESS_DENIED';
export interface ImportItem { itemId: string; importId: string; ordinal: number; revision: bigint; state: ItemState; failureCode?: FailureCode; canUpload: boolean; file?: FileIntent; receipt?: Receipt }
export interface Batch { importId: string; createdAt: string; state: 'OPEN' | 'COMPLETED' | 'PARTIAL_FAILED' | 'FAILED'; counts: { pending: number; processing: number; accepted: number; failed: number }; canContinue: boolean; spaceId?: string; securityLevel?: number }
const root = '/api/document-imports';
const maxLong = 9223372036854775807n;
const broken = () => new ImportError(503);
export class ImportError extends ApiError {
  constructor(status: number) {
    super(status);
    this.message = ({ 0: '导入结果待确认，请核对任务。', 404: '任务不可访问或服务暂不支持批量导入。',
      405: '当前服务暂不支持批量导入。', 409: '文件、请求绑定或执行状态冲突，请先核对当前项。',
      429: '导入上传容量繁忙，已暂停后续发送，请稍后主动继续。', 503: '导入结果尚未确认，请核对任务，不会自动重传。' } as Record<number, string>)[status] ?? this.message;
  }
}
export const importMessage = (error: unknown) => error instanceof ApiError ? new ImportError(error.status).message : new ImportError(0).message;
function object(value: unknown): Record<string, unknown> { if (!value || typeof value !== 'object' || Array.isArray(value)) throw broken(); return value as Record<string, unknown>; }
function text(value: unknown): string { if (typeof value !== 'string') throw broken(); return value; }
function bool(value: unknown): boolean { if (typeof value !== 'boolean') throw broken(); return value; }
function id(value: unknown): string { try { if (typeof value !== 'string') throw broken(); return positiveId(value); } catch { throw broken(); } }
function integer(value: unknown, min: number, max: number): number { if (typeof value !== 'number' || !Number.isSafeInteger(value) || value < min || value > max) throw broken(); return value; }
function enumeration<T extends string>(value: unknown, choices: readonly T[]): T { if (typeof value !== 'string' || !choices.includes(value as T)) throw broken(); return value as T; }
export function revision(value: unknown): bigint {
  if ((typeof value !== 'number' || !Number.isSafeInteger(value)) && typeof value !== 'bigint') throw broken();
  const result = BigInt(value as number | bigint); if (result < 0n || result > maxLong) throw broken(); return result;
}
export function cleanText(value: string, max: number, required = true): string {
  // 与服务端 Character.isWhitespace/isSpaceChar 的首尾规范化一致。
  const result = value.replace(/^[\p{Z}\u0009-\u000d\u001c-\u001f]+|[\p{Z}\u0009-\u000d\u001c-\u001f]+$/gu, '');
  if ((required && !result) || [...result].length > max || /[\uD800-\uDFFF]/u.test(result) || result.includes('\0')) throw new ImportError(400);
  return result;
}
export function fileName(value: string): string {
  const name = cleanText(value, 255);
  if (name.length > 255 || /[\/\\\p{Cc}]/u.test(name) || name === '.' || name === '..') throw new ImportError(400);
  return name;
}
export function decodeReceipt(value: unknown): Receipt {
  const row = object(value);
  return { documentId: id(row.documentId), versionId: id(row.versionId), parseTaskId: id(row.parseTaskId) };
}
function decodeFile(value: unknown): FileIntent {
  const row = object(value), name = text(row.fileName), title = text(row.title), hash = text(row.sha256), key = text(row.itemKey);
  try { if (fileName(name) !== name || cleanText(title, 500) !== title) throw broken(); } catch { throw broken(); }
  if (!/^[a-f0-9]{64}$/.test(hash) || !/^[A-Za-z0-9][A-Za-z0-9._:-]{0,127}$/.test(key)) throw broken();
  return { itemKey: key, fileName: name, title, sizeBytes: integer(row.sizeBytes, 1, Number.MAX_SAFE_INTEGER), sha256: hash };
}
export function decodeItem(value: unknown): ImportItem {
  const row = object(value), state = enumeration(row.state, ['PENDING', 'PROCESSING', 'ACCEPTED', 'FAILED']);
  const receipt = row.receipt == null ? undefined : decodeReceipt(row.receipt);
  const failureCode = row.failureCode == null ? undefined : enumeration(row.failureCode, ['INVALID_FILE', 'PAYLOAD_TOO_LARGE', 'STORAGE_UNAVAILABLE', 'EXPIRED', 'ACCESS_DENIED']);
  const file = row.file == null ? undefined : decodeFile(row.file), canUpload = bool(row.canUpload);
  if ((state === 'ACCEPTED') !== !!receipt || (state === 'FAILED') !== !!failureCode
    || (canUpload && (!file || !['PENDING', 'FAILED'].includes(state) || failureCode === 'INVALID_FILE' || failureCode === 'PAYLOAD_TOO_LARGE'))) throw broken();
  return { itemId: id(row.itemId), importId: id(row.importId), ordinal: integer(row.ordinal, 1, 500), revision: revision(row.revision), state, failureCode, file, receipt, canUpload };
}
export function decodeBatch(value: unknown): Batch {
  const row = object(value), raw = object(row.counts);
  const counts = { pending: integer(raw.pending, 0, 500), processing: integer(raw.processing, 0, 500), accepted: integer(raw.accepted, 0, 500), failed: integer(raw.failed, 0, 500) };
  const total = Object.values(counts).reduce((a, b) => a + b, 0), canContinue = bool(row.canContinue);
  const expected = counts.pending || counts.processing ? 'OPEN' : counts.failed ? counts.accepted ? 'PARTIAL_FAILED' : 'FAILED' : 'COMPLETED';
  const state = enumeration(row.state, ['OPEN', 'COMPLETED', 'PARTIAL_FAILED', 'FAILED']);
  const createdAt = text(row.createdAt);
  if (total < 1 || total > 500 || state !== expected || !Number.isFinite(Date.parse(createdAt))) throw broken();
  if (!canContinue && (row.spaceId != null || row.securityLevel != null)) throw broken();
  return { importId: id(row.importId), createdAt, counts, state, canContinue,
    ...(canContinue ? { spaceId: id(row.spaceId), securityLevel: integer(row.securityLevel, -32768, 32767) } : {}) };
}
export function decodeCapabilities(value: unknown): Capabilities {
  const row = object(value);
  if (row.protocolVersion !== 1 || !Array.isArray(row.extensions) || !row.extensions.length
      || row.extensions.some(value => typeof value !== 'string' || !/^[a-z0-9]{1,10}$/.test(value))) throw broken();
  return { protocolVersion: 1, maxItems: integer(row.maxItems, 1, 500), maxTotalBytes: integer(row.maxTotalBytes, 1, 107374182400),
    maxFileBytes: integer(row.maxFileBytes, 1, Number.MAX_SAFE_INTEGER), maxManifestBytes: integer(row.maxManifestBytes, 1, 1048576),
    uploadConcurrency: integer(row.uploadConcurrency, 1, 2), extensions: row.extensions as string[] };
}
function page<T>(value: unknown, decode: (value: unknown) => T): Page<T> {
  const row = object(value); if (!Array.isArray(row.items) || row.items.length > 100) throw broken();
  const hasMore = bool(row.hasMore), nextCursor = row.nextCursor === null ? null : text(row.nextCursor);
  if (hasMore !== !!nextCursor || (nextCursor && (!/^[A-Za-z0-9_-]{1,128}$/.test(nextCursor) || !row.items.length))) throw broken();
  return { items: row.items.map(decode), hasMore, nextCursor };
}
export function createIntent(manifest: Manifest, capabilities: Capabilities): CreateIntent {
  if (!manifest.items.length || manifest.items.length > capabilities.maxItems || !Number.isInteger(manifest.securityLevel)
      || manifest.securityLevel < -32768 || manifest.securityLevel > 32767) throw new ImportError(400);
  const items = manifest.items.map(file => ({ ...file, fileName: fileName(file.fileName), title: cleanText(file.title, 500, false) || fileName(file.fileName) }));
  items.forEach(decodeFile);
  if (new Set(items.map(file => file.itemKey)).size !== items.length || items.some(file => file.sizeBytes > capabilities.maxFileBytes)
      || items.reduce((sum, file) => sum + BigInt(file.sizeBytes), 0n) > BigInt(capabilities.maxTotalBytes)) throw new ImportError(400);
  const body = encodeJson({ spaceId: BigInt(positiveId(manifest.spaceId)), securityLevel: manifest.securityLevel, items });
  if (new TextEncoder().encode(body).length > capabilities.maxManifestBytes) throw new ImportError(413);
  return Object.freeze({ requestId: crypto.randomUUID(), body });
}
async function call(session: Session, path: string, options: RequestInit = {}): Promise<unknown> {
  const epoch = session.getSnapshot().epoch;
  const timeout = AbortSignal.timeout(path.endsWith('/file') ? 180000 : 15000);
  options = { ...options, signal: options.signal ? AbortSignal.any([options.signal, timeout]) : timeout };
  try {
    const value = await readJson<unknown>(await session.send(path, options));
    if (epoch !== session.getSnapshot().epoch || options.signal?.aborted) throw new DOMException('请求已停止', 'AbortError');
    return value;
  } catch (error) { if (error instanceof ApiError) throw new ImportError(error.status); throw error; }
}
const query = (cursor: string | null, limit = 20) => `?limit=${limit}${cursor ? `&cursor=${encodeURIComponent(cursor)}` : ''}`;
export const importApi = {
  capabilities: async (s: Session, signal?: AbortSignal) => decodeCapabilities(await call(s, `${root}/capabilities`, { signal })),
  create: async (s: Session, intent: CreateIntent, signal?: AbortSignal): Promise<Created> => {
    const row = object(await call(s, root, { method: 'POST', signal, headers: { 'Content-Type': 'application/json', 'X-Request-Id': intent.requestId }, body: intent.body }));
    return { importId: id(row.importId), replayed: bool(row.replayed) };
  },
  resolve: async (s: Session, requestId: string, signal?: AbortSignal): Promise<string | null> => {
    const row = object(await call(s, `${root}/resolve`, { method: 'POST', signal, headers: { 'Content-Type': 'application/json' }, body: encodeJson({ requestId }) }));
    if (!bool(row.found)) { if (row.importId !== null) throw broken(); return null; }
    return id(row.importId);
  },
  list: async (s: Session, cursor: string | null, signal?: AbortSignal) => page(await call(s, root + query(cursor), { signal }), decodeBatch),
  batch: async (s: Session, batchId: string, signal?: AbortSignal) => {
    const value = decodeBatch(await call(s, `${root}/${positiveId(batchId)}`, { signal })); if (value.importId !== batchId) throw broken(); return value;
  },
  items: async (s: Session, batchId: string, cursor: string | null, signal?: AbortSignal, limit = 20) => {
    const value = page(await call(s, `${root}/${positiveId(batchId)}/items${query(cursor, limit)}`, { signal }), decodeItem);
    if (value.items.some((item, index) => item.importId !== batchId || (index > 0 && item.ordinal <= value.items[index - 1]!.ordinal))
        || new Set(value.items.map(item => item.itemId)).size !== value.items.length) throw broken();
    return value;
  },
  item: async (s: Session, batchId: string, itemId: string, signal?: AbortSignal) => {
    const value = decodeItem(await call(s, `${root}/${positiveId(batchId)}/items/${positiveId(itemId)}`, { signal }));
    if (value.importId !== batchId || value.itemId !== itemId) throw broken(); return value;
  },
  upload: async (s: Session, item: ImportItem, file: File, requestId: string, signal?: AbortSignal) => {
    const body = new FormData(); body.set('file', file); body.set('expectedRevision', revision(item.revision).toString());
    const timeout = AbortSignal.timeout(180000), combined = signal ? AbortSignal.any([signal, timeout]) : timeout;
    return decodeReceipt(await call(s, `${root}/${positiveId(item.importId)}/items/${positiveId(item.itemId)}/file`,
      { method: 'POST', headers: { 'X-Request-Id': requestId }, body, signal: combined }));
  },
};
