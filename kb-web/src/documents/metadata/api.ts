import { ApiError, decodeJson, encodeJson, positiveId, readJson } from '../../api';
import { exactVersion, numericId, type WriteIntent } from '../../admin/api';
import type { Version } from '../../admin/types';
import type { Session } from '../../session';
import type { DocumentPage, QueryPage } from '../../types';

export interface PathNode { id: string; name: string }
export interface Category { id: string; name: string; path: PathNode[] }
export interface CategoryItem extends Category { parentId: string | null; sortOrder: number }
export interface CategoryPage extends QueryPage<CategoryItem> { hasMore: boolean; treeVersion: Version }
export interface Tag { id: string; name: string; enabled: boolean }
export interface ManagedTag extends Tag { version: Version; createdAt: string; updatedAt: string }
export interface Metadata { documentId: string; spaceId: string; title: string; summary: string | null; category: Category | null; tags: Tag[]; metadataVersion: Version; updatedAt: string }
export interface MetadataDraft { title: string; summary: string | null; categoryId: string | null; tagIds: string[]; expectedMetadataVersion: Version }
export interface Receipt { documentId: string; metadataVersion: Version; updatedAt: string; changedFields: string[]; replayed: boolean }
export const fileTypes = ['PDF', 'TXT', 'MARKDOWN', 'WORD', 'EXCEL', 'POWERPOINT', 'OTHER', 'UNPUBLISHED'] as const;
export interface Filters { titleQuery?: string; spaceId?: string; categoryId?: string; uncategorized?: boolean; tagIds?: string[]; fileType?: typeof fileTypes[number]; updatedFrom?: string; updatedTo?: string }
export type CategoryContext = { kind: 'edit' | 'manage' | 'filter'; id: string };
const reasons = {
  METADATA_VERSION_CONFLICT: '文档资料已变化，请重新读取后编辑。', TREE_VERSION_CONFLICT: '分类目录已变化，请重新读取。',
  TAG_VERSION_CONFLICT: '标签已变化，请重新读取。', CATEGORY_UNAVAILABLE: '分类不可选，请重新选择。', TAG_UNAVAILABLE: '标签不可选，请重新选择。',
  NAME_CONFLICT: '名称已被占用。', REFERENCE_CONFLICT: '分类仍有子节点或文档引用，不能删除。', REQUEST_MISMATCH: '请求标识与原操作不匹配。',
  NO_CHANGE: '资料没有变化。', TREE_STRUCTURE_CONFLICT: '分类不能移入自身、后代或超过八层的位置。', SPACE_ARCHIVED: '空间已归档，目录只读。',
} as const;
export class MetadataError extends ApiError {
  constructor(public readonly reason: keyof typeof reasons) { super(409); this.message = reasons[reason]; }
}
export async function metadataFailure(response: Response): Promise<ApiError> {
  if (response.status !== 409 || !response.body || !response.headers.get('Content-Type')?.includes('application/json')) {
    await response.body?.cancel().catch(() => {}); return new ApiError(response.status);
  }
  const reader = response.body.getReader(), parts: Uint8Array[] = []; let length = 0;
  try {
    for (;;) { const part = await reader.read(); if (part.done) break; length += part.value.length;
      if (length > 8192) { await reader.cancel(); return new ApiError(response.status); } parts.push(part.value); }
    const bytes = new Uint8Array(length); let offset = 0;
    parts.forEach(part => { bytes.set(part, offset); offset += part.length; });
    const value = decodeJson<Record<string, unknown>>(new TextDecoder('utf-8', { fatal: true }).decode(bytes));
    if (value.code === 500 && value.data === null && typeof value.reasonCode === 'string' && Object.hasOwn(reasons, value.reasonCode))
      return new MetadataError(value.reasonCode as keyof typeof reasons);
  } catch (error) { if (error instanceof Error && error.name === 'AbortError') throw error; }
  finally { reader.releaseLock(); }
  return new ApiError(response.status);
}
async function call<T>(session: Session, path: string, body?: unknown, signal?: AbortSignal): Promise<T> {
  const response = await session.send(path, body === undefined ? { signal } : { signal, method: 'POST', headers: { 'Content-Type': 'application/json' }, body: encodeJson(body) });
  if (!response.ok) throw await metadataFailure(response);
  return readJson<T>(response);
}
const broken = () => new ApiError(503);
function object(value: unknown): Record<string, unknown> { if (!value || typeof value !== 'object' || Array.isArray(value)) throw broken(); return value as Record<string, unknown>; }
function text(value: unknown): string { if (typeof value !== 'string') throw broken(); return value; }
function boolean(value: unknown): boolean { if (typeof value !== 'boolean') throw broken(); return value; }
function id(value: unknown): string { try { if (typeof value !== 'string') throw broken(); return positiveId(value); } catch { throw broken(); } }
function version(value: unknown): Version { try { exactVersion(value as Version); return value as Version; } catch { throw broken(); } }
function list(value: unknown): unknown[] { if (!Array.isArray(value)) throw broken(); return value; }
function category(value: unknown): Category {
  const row = object(value), path = list(row.path).map(item => { const node = object(item); return { id: id(node.id), name: text(node.name) }; });
  if (!path.length || path.length > 8 || path.at(-1)?.id !== id(row.id)) throw broken();
  return { id: id(row.id), name: text(row.name), path };
}
function tag(value: unknown): Tag { const row = object(value); return { id: id(row.id), name: text(row.name), enabled: boolean(row.enabled) }; }
export function decodeMetadata(value: unknown): Metadata {
  const row = object(value), tags = list(row.tags).map(tag);
  if (tags.length > 20 || new Set(tags.map(tag => tag.id)).size !== tags.length) throw broken();
  return { documentId: id(row.documentId), spaceId: id(row.spaceId), title: text(row.title), summary: row.summary === null ? null : text(row.summary),
    category: row.category === null ? null : category(row.category), tags, metadataVersion: version(row.metadataVersion), updatedAt: text(row.updatedAt) };
}
export function decodeCategoryPage(value: unknown): CategoryPage {
  const row = object(value), items = list(row.items).map(value => {
    const item = object(value); if (!Number.isInteger(item.sortOrder) || Number(item.sortOrder) < 0 || Number(item.sortOrder) > 2147483647) throw broken();
    return { ...category(item), parentId: item.parentId === null ? null : id(item.parentId), sortOrder: item.sortOrder as number };
  });
  if (items.length > 100) throw broken();
  return { items, hasMore: boolean(row.hasMore), nextCursor: row.nextCursor === null ? null : text(row.nextCursor), treeVersion: version(row.treeVersion) };
}
export function cleanText(value: string, max: number, required = true, dictionary = false): string {
  const result = (dictionary ? value.normalize('NFC') : value).trim();
  if ((required && !result) || [...result].length > max || /[\uD800-\uDFFF]/u.test(result) || result.includes('\0') || (dictionary && /\p{Cc}/u.test(result))) throw new ApiError(400);
  return result;
}
function ids(values: string[]) {
  const unique = new Set(values.map(positiveId));
  if (values.length > 20 || values.length !== unique.size) throw new ApiError(400);
  return [...unique].map(numericId).sort((a, b) => a < b ? -1 : a > b ? 1 : 0);
}
export const metadataWrites = {
  update: (id: string, input: MetadataDraft): WriteIntent => ({ method: 'PUT', path: `/api/documents/${positiveId(id)}/metadata`, body: {
    title: cleanText(input.title, 512), summary: input.summary === null ? null : cleanText(input.summary, 2000, false) || null,
    categoryId: input.categoryId === null ? null : numericId(input.categoryId), tagIds: ids(input.tagIds), expectedMetadataVersion: exactVersion(input.expectedMetadataVersion),
  } }),
  category: (space: string, category: string | null, name: string, parent: string | null, order: number, expected: Version): WriteIntent => {
    if (!Number.isInteger(order) || order < 0 || order > 2147483647) throw new ApiError(400);
    return { method: category === null ? 'POST' : 'PUT', path: `/api/admin/spaces/${positiveId(space)}/categories${category === null ? '' : `/${positiveId(category)}`}`,
      body: { name: cleanText(name, 64, true, true), parentId: parent === null ? null : numericId(parent), sortOrder: order, expectedTreeVersion: exactVersion(expected) } };
  },
  deleteCategory: (space: string, category: string, expected: Version): WriteIntent => ({ method: 'DELETE', path: `/api/admin/spaces/${positiveId(space)}/categories/${positiveId(category)}`, body: { expectedTreeVersion: exactVersion(expected) } }),
  tag: (tag: ManagedTag | null, name: string, enabled: boolean): WriteIntent => ({ method: tag ? 'PUT' : 'POST', path: `/api/admin/document-tags${tag ? `/${positiveId(tag.id)}` : ''}`,
    body: tag ? { name: cleanText(name, 32, true, true), enabled, expectedVersion: exactVersion(tag.version) } : { name: cleanText(name, 32, true, true) } }),
};
export async function metadataWrite<T>(session: Session, intent: WriteIntent, requestId: string, signal?: AbortSignal): Promise<T> {
  const epoch = session.getSnapshot().epoch;
  const response = await session.send(intent.path, { method: intent.method, signal, headers: { 'Content-Type': 'application/json', 'X-Request-Id': requestId }, body: encodeJson(intent.body) });
  if (!response.ok) throw await metadataFailure(response);
  const data = object(await readJson<unknown>(response));
  if (epoch !== session.getSnapshot().epoch || signal?.aborted) throw new DOMException('请求已停止', 'AbortError');
  boolean(data.replayed);
  if (intent.path.endsWith('/metadata')) { if (id(data.documentId) !== intent.path.split('/')[3]) throw broken(); version(data.metadataVersion); text(data.updatedAt);
    const changed = list(data.changedFields); if (changed.some(field => typeof field !== 'string' || !['title', 'summary', 'categoryId', 'tagIds'].includes(field))
      || !changed.length || changed.length > 4 || new Set(changed).size !== changed.length) throw broken();
  } else if (intent.path.includes('/categories')) { id(data.categoryId); version(data.treeVersion); }
  else { id(data.tagId); version(data.version); }
  return data as T;
}
export const metadataApi = {
  get: async (session: Session, id: string, signal?: AbortSignal) => {
    const value = decodeMetadata(await call(session, `/api/documents/${positiveId(id)}/metadata`, undefined, signal));
    if (value.documentId !== id) throw broken();
    return value;
  },
  categories: async (session: Session, context: CategoryContext, parent: string | null, cursor: string | null, signal?: AbortSignal) => {
    const resource = positiveId(context.id), params = new URLSearchParams();
    if (parent !== null) params.set('parentId', positiveId(parent)); if (cursor) params.set('cursor', cursor);
    const value = context.kind === 'filter' ? await call(session, '/api/documents/query/categories', { spaceId: numericId(resource), parentId: parent === null ? null : numericId(parent), ...(cursor ? { cursor } : {}) }, signal)
      : await call(session, `${context.kind === 'manage' ? `/api/admin/spaces/${resource}/categories` : `/api/documents/${resource}/metadata-options/categories`}${params.size ? `?${params}` : ''}`, undefined, signal);
    return decodeCategoryPage(value);
  },
  tags: async (session: Session, kind: 'manage' | 'edit' | 'filter', document: string | null, name: string, cursor: string | null, signal?: AbortSignal, enabled?: boolean): Promise<QueryPage<ManagedTag>> => {
    const path = kind === 'manage' ? '/api/admin/document-tags/query' : kind === 'edit' ? `/api/documents/${positiveId(document)}/metadata-options/tags` : '/api/documents/query/tags';
    const result = object(await call(session, path, { nameQuery: cleanText(name, 64, false), ...(cursor ? { cursor } : {}), ...(kind === 'manage' && enabled !== undefined ? { enabled } : {}) }, signal));
    const items = list(result.items).map(value => { const row = object(value); return { ...tag(row), version: kind === 'manage' ? version(row.version) : 0, createdAt: kind === 'manage' ? text(row.createdAt) : '', updatedAt: kind === 'manage' ? text(row.updatedAt) : '' }; });
    if (items.length > 100) throw broken();
    return { items, hasMore: boolean(result.hasMore), nextCursor: result.nextCursor === null ? null : text(result.nextCursor) };
  },
  query: (session: Session, filters: Filters, page: number, signal?: AbortSignal) => call<DocumentPage>(session, '/api/documents/query', queryBody(filters, page), signal),
};
export function queryBody(filters: Filters, page: number) {
  if (!Number.isInteger(page) || page < 1 || page > 2147483647 || (filters.categoryId && filters.uncategorized)) throw new ApiError(400);
  if (filters.fileType && !fileTypes.includes(filters.fileType)) throw new ApiError(400);
  for (const date of [filters.updatedFrom, filters.updatedTo]) if (date && (!/(Z|[+-]\d\d:\d\d)$/.test(date) || !Number.isFinite(Date.parse(date)))) throw new ApiError(400);
  if (filters.updatedFrom && filters.updatedTo && Date.parse(filters.updatedFrom) >= Date.parse(filters.updatedTo)) throw new ApiError(400);
  return { ...filters, titleQuery: cleanText(filters.titleQuery ?? '', 128, false), spaceId: filters.spaceId ? numericId(filters.spaceId) : undefined,
    categoryId: filters.categoryId ? numericId(filters.categoryId) : undefined, tagIds: ids(filters.tagIds ?? []), page, size: 20 };
}
