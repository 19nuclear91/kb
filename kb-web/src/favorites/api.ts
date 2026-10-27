import { ApiError, positiveId } from '../api';
import type { Session } from '../session';

export interface FavoriteState { documentId: string; favorited: boolean }
export interface FavoriteItem {
  documentId: string; title: string; spaceId: string; securityLevel: number;
  currentVersionId: string | null; currentVersionNo: number | null; favoritedAt: string;
}
export interface FavoritePage { items: FavoriteItem[]; hasMore: boolean; nextCursor: string | null }
const base = '/api/documents/favorites';

export function uniqueIds(ids: readonly string[]): string[] { return [...new Set(ids.map(positiveId))]; }

function checkedState(value: FavoriteState, ids: readonly string[]): FavoriteState {
  if (!value || typeof value.documentId !== 'string' || !ids.includes(value.documentId) || typeof value.favorited !== 'boolean') throw new ApiError(503);
  return { documentId: value.documentId, favorited: value.favorited };
}

export async function favoriteStates(session: Session, ids: readonly string[], signal: AbortSignal): Promise<FavoriteState[]> {
  const requested = uniqueIds(ids);
  if (!requested.length) return [];
  if (requested.length > 100) throw new ApiError(400);
  const result = await session.post<{ items: FavoriteState[] }>(`${base}/status`, { documentIds: requested.map(BigInt) }, true, signal);
  if (!result || !Array.isArray(result.items) || result.items.length > requested.length) throw new ApiError(503);
  const states = result.items.map(value => checkedState(value, requested));
  if (new Set(states.map(value => value.documentId)).size !== states.length) throw new ApiError(503);
  return states;
}

export async function setFavorite(session: Session, id: string, favorited: boolean, signal: AbortSignal): Promise<FavoriteState> {
  const documentId = positiveId(id);
  const result = await session.json<FavoriteState>(`/api/documents/${documentId}/favorite`, { method: favorited ? 'PUT' : 'DELETE', signal });
  const state = checkedState(result, [documentId]);
  if (state.favorited !== favorited) throw new ApiError(503);
  return state;
}

export async function favoritePage(session: Session, cursor: string | null, signal: AbortSignal): Promise<FavoritePage> {
  if (cursor != null && (!/^[A-Za-z0-9_-]+$/.test(cursor) || cursor.length > 2731)) throw new ApiError(400);
  const result = await session.json<FavoritePage>(`${base}?limit=20${cursor ? `&cursor=${encodeURIComponent(cursor)}` : ''}`, { signal });
  if (!result || !Array.isArray(result.items) || result.items.length > 20 || typeof result.hasMore !== 'boolean'
    || (result.hasMore ? typeof result.nextCursor !== 'string' || !/^[A-Za-z0-9_-]{1,2731}$/.test(result.nextCursor) || !result.items.length : result.nextCursor !== null)) throw new ApiError(503);
  const seen = new Set<string>();
  for (const item of result.items) {
    if (!item || typeof item.documentId !== 'string' || seen.has(item.documentId) || typeof item.spaceId !== 'string'
      || typeof item.title !== 'string' || !Number.isInteger(item.securityLevel) || item.securityLevel < 0
      || typeof item.favoritedAt !== 'string' || !Number.isFinite(Date.parse(item.favoritedAt))
      || (item.currentVersionId === null ? item.currentVersionNo !== null : typeof item.currentVersionId !== 'string'
        || !Number.isInteger(item.currentVersionNo) || Number(item.currentVersionNo) < 1)) throw new ApiError(503);
    try { positiveId(item.documentId); positiveId(item.spaceId); if (item.currentVersionId !== null) positiveId(item.currentVersionId); }
    catch { throw new ApiError(503); }
    seen.add(item.documentId);
  }
  return result;
}
