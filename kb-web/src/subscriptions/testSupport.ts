import { vi } from 'vitest';
import { Session } from '../session';
import { me, storage } from '../test/fixtures';
import type { Candidate, Detail, Receipt } from './types';
export const id = 'aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee', generation = 'bbbbbbbb-cccc-dddd-eeee-ffffffffffff';
export const at = '2026-11-27T08:00:00Z';
export const response = (data: unknown) => new Response(JSON.stringify({ code: 200, message: 'ok', data, ok: true }), { headers: { 'Content-Type': 'application/json' } });
export const page = (items: unknown[] = [], nextCursor: string | null = null) => ({ items, hasMore: nextCursor !== null, nextCursor, asOf: at });
export const target: Candidate = { kind: 'DOCUMENT', targetId: '9007199254740993', label: '订阅资料', enabled: true, subscriptionId: null, generationId: null, version: '0', state: null };
export const preference = { mode: 'WEEKLY', version: '0', effectiveAt: null, timeZone: 'Asia/Shanghai', weeklySchedule: '每周一09:00' };
export const detail: Detail = { update: { updateId: id, documentId: '9007199254740993', versionId: '9007199254740995', versionNo: 3, title: '发布资料', publishedAt: at, weekStart: '2026-11-23', mode: 'WEEKLY' },
  hasNote: true, text: '人工变更记录', noteVersion: '2', corrected: true, createdAfterVisible: true, baseVersionId: '9007199254740994', baseVersionNo: 1, comparisonStatus: 'BASE_AVAILABLE' };
export const receipt = (requestId: string): Receipt => ({ requestId, operation: 'SUBSCRIBE', subscriptionId: id, generationId: generation, version: '1', state: 'ACTIVE', mode: null, effectiveAt: at, replayed: false });
export async function ready(next: (path: string, options: RequestInit) => Response | Promise<Response>, identity = me) {
  const saved = storage(), transport = vi.fn(async (url: RequestInfo | URL, options: RequestInit = {}) => String(url) === '/api/auth/me' ? response(identity) : next(String(url), options));
  const session = new Session(saved, transport as typeof fetch); await session.refresh(); transport.mockClear(); return { session, transport, saved };
}
