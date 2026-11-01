import { ApiError, encodeJson, positiveId, readJson } from '../api';
import { numericId } from '../admin/api';
import { cleanText, decodeCategoryPage, type Tag } from '../documents/metadata/api';
import type { Session } from '../session';
import type { Hit, QueryPage } from '../types';

export const searchFileTypes = ['PDF', 'TXT', 'MARKDOWN', 'WORD', 'EXCEL', 'POWERPOINT', 'OTHER'] as const;
export interface SearchFilters {
  spaceId?: string | null; categoryId?: string | null; uncategorized?: boolean; tagIds?: readonly string[];
  fileType?: typeof searchFileTypes[number]; updatedFrom?: string; updatedTo?: string;
}
export class SearchProtocolError extends ApiError {
  constructor() { super(503); this.message = '无法确认服务端筛选支持，请稍后重试或主动调整条件。'; }
}

// 比较到纳秒，原始时区和小数位随请求保留。
function instant(value: string): bigint {
  const match = /^(\d{4})-(\d\d)-(\d\d)T(\d\d):(\d\d)(?::(\d\d)(?:\.(\d{1,9}))?)?(Z|[+-]\d\d:\d\d)$/.exec(value);
  if (!match) throw new ApiError(400);
  const [, year, month, day, hour, minute, second = '00', fraction = '', zone] = match;
  const date = new Date(0); date.setUTCFullYear(+year, +month - 1, +day); date.setUTCHours(+hour, +minute, +second, 0);
  if (date.getUTCFullYear() !== +year || date.getUTCMonth() !== +month - 1 || date.getUTCDate() !== +day
    || +hour > 23 || +minute > 59 || +second > 59) throw new ApiError(400);
  const offset = zone === 'Z' ? 0 : (+zone.slice(1, 3) * 60 + +zone.slice(4)) * (zone[0] === '-' ? -1 : 1);
  if (Math.abs(offset) > 1080 || (zone !== 'Z' && +zone.slice(4) > 59)) throw new ApiError(400);
  return BigInt(date.getTime() - offset * 60000) * 1000000n + BigInt(fraction.padEnd(9, '0'));
}
export function localSearchTime(value: string): string | undefined {
  if (!value) return undefined;
  const match = /^(\d{4}-\d\d-\d\dT\d\d:\d\d)(?::(\d\d)(\.\d{1,9})?)?$/.exec(value);
  if (!match) throw new ApiError(400);
  const base = `${match[1]}:${match[2] ?? '00'}`, date = new Date(base);
  if (!Number.isFinite(date.getTime())) throw new ApiError(400);
  const minutes = -date.getTimezoneOffset(), absolute = Math.abs(minutes);
  const zone = `${minutes < 0 ? '-' : '+'}${String(Math.floor(absolute / 60)).padStart(2, '0')}:${String(absolute % 60).padStart(2, '0')}`;
  const result = `${base}${match[3] ?? ''}${zone}`;
  // 夏令时跳过的本地时间不能被 Date 静默挪动。
  if (instant(`${base}${zone}`) !== BigInt(date.getTime()) * 1000000n) throw new ApiError(400);
  instant(result); return result;
}
export function searchBody(query: string, filters: SearchFilters = {}) {
  const tags = (filters.tagIds ?? []).map(id => numericId(positiveId(id)));
  if (tags.length > 20 || new Set(tags).size !== tags.length || (filters.categoryId != null && filters.uncategorized)) throw new ApiError(400);
  if (filters.uncategorized !== undefined && typeof filters.uncategorized !== 'boolean') throw new ApiError(400);
  if (filters.fileType !== undefined && !searchFileTypes.includes(filters.fileType)) throw new ApiError(400);
  const from = filters.updatedFrom === undefined ? undefined : instant(filters.updatedFrom);
  const to = filters.updatedTo === undefined ? undefined : instant(filters.updatedTo);
  if (from !== undefined && to !== undefined && from >= to) throw new ApiError(400);
  return { query, candidates: 50, topK: 20,
    ...(filters.spaceId != null ? { spaceId: numericId(filters.spaceId) } : {}),
    ...(filters.categoryId != null ? { categoryId: numericId(filters.categoryId) } : {}),
    ...(filters.uncategorized ? { uncategorized: true } : {}),
    ...(tags.length ? { tagIds: tags.sort((a, b) => a < b ? -1 : a > b ? 1 : 0) } : {}),
    ...(filters.fileType !== undefined ? { fileType: filters.fileType } : {}),
    ...(from !== undefined ? { updatedFrom: filters.updatedFrom } : {}), ...(to !== undefined ? { updatedTo: filters.updatedTo } : {}) };
}
export function hasSearchFilters(filters: SearchFilters): boolean {
  return Object.keys(searchBody('', filters)).length > 3;
}
async function post<T>(session: Session, path: string, body: unknown, signal?: AbortSignal, restricted = false): Promise<T> {
  const epoch = session.getSnapshot().epoch;
  const current = () => { if (signal?.aborted || session.getSnapshot().epoch !== epoch) throw new DOMException('请求已停止', 'AbortError'); };
  const response = await session.send(path, { method: 'POST', headers: { 'Content-Type': 'application/json' }, body: encodeJson(body), signal });
  try { current(); } catch (error) { await response.body?.cancel().catch(() => {}); throw error; }
  if (response.ok && restricted && response.headers.get('X-Search-Filter-Version') !== '1') {
    await response.body?.cancel().catch(() => {}); throw new SearchProtocolError();
  }
  const result = await readJson<T>(response); current(); return result;
}
export const searchApi = {
  search: async (session: Session, query: string, filters: SearchFilters, signal?: AbortSignal): Promise<Hit[]> => {
    const body = searchBody(query, filters);
    const result = await post<Hit[]>(session, '/api/search', body, signal, Object.keys(body).length > 3);
    if (!Array.isArray(result) || result.length > 20 || result.some(hit => !hit || typeof hit.content !== 'string')) throw new ApiError(503);
    return result;
  },
  categories: async (session: Session, space: string, parent: string | null, cursor: string | null, signal?: AbortSignal) => {
    const page = decodeCategoryPage(await post(session, '/api/search/categories', {
      spaceId: numericId(space), parentId: parent === null ? null : numericId(parent), ...(cursor ? { cursor } : {}),
    }, signal));
    if (page.hasMore !== !!page.nextCursor) throw new ApiError(503);
    return page;
  },
  tags: async (session: Session, name: string, cursor: string | null, signal?: AbortSignal): Promise<QueryPage<Tag>> => {
    const page = await post<QueryPage<Tag>>(session, '/api/search/tags', { nameQuery: cleanText(name, 64, false), ...(cursor ? { cursor } : {}) }, signal);
    if (!page || !Array.isArray(page.items) || page.items.length > 100 || typeof page.hasMore !== 'boolean'
      || (page.nextCursor !== null && typeof page.nextCursor !== 'string') || page.hasMore !== !!page.nextCursor
      || page.items.some(tag => !tag || typeof tag.id !== 'string' || typeof tag.name !== 'string' || typeof tag.enabled !== 'boolean')) throw new ApiError(503);
    try { page.items.forEach(tag => positiveId(tag.id)); } catch { throw new ApiError(503); }
    return page;
  },
};
export function searchOptionsMessage(error: unknown): string {
  if (error instanceof ApiError && error.status === 409) return '分类目录已变化，请重新读取。';
  if (error instanceof ApiError && error.status === 400) return '候选查询不符合要求，请调整或重新读取。';
  if (error instanceof ApiError && error.status === 429) return '候选读取过于频繁，请稍后主动重试。';
  return '搜索候选暂不可用，请主动重新读取。';
}
