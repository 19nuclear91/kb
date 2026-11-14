import { vi } from 'vitest';
import { encodeJson } from '../api';
import { Session } from '../session';
import { me, storage } from '../test/fixtures';

export const eventId = 'aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee';
export const solutionId = '11111111-2222-3333-4444-555555555555';
export const instant = '2026-11-14T04:00:00Z';
export const signal = new AbortController().signal;
export const participant = (userId = 1n) => ({ userId, label: '显示标签' });
export const item = (over: Record<string, unknown> = {}) => ({ gapId: 9007199254740993n, category: 'MISSING_KNOWLEDGE', source: 'MANUAL',
  state: 'SUBMITTED', version: 0n, reporter: participant(), assignee: null, needsReassignment: false, createdAt: instant, updatedAt: instant, ...over });
export const capabilities = { assign: false, note: true, returnToReception: false, submitSolution: false,
  confirm: false, rejectSolution: false, withdraw: true, close: false };
export const detail = (over: Record<string, unknown> = {}) => ({ ...item(), description: '明确分享的描述', consentVersion: 'GAP_SHARE_V1',
  capabilities: { ...capabilities }, currentSolution: null, ...over });
export const entry = (over: Record<string, unknown> = {}) => ({ entryId: solutionId, position: 2n, kind: 'SOLUTION', author: participant(2n),
  text: '人工说明', referenceState: 'NONE', reference: null, createdAt: instant, ...over });
export const history = (over: Record<string, unknown> = {}) => ({ eventId, kind: 'GAP_CREATED', resultVersion: 0n, actor: participant(),
  assignee: null, occurredAt: instant, solutionId: null, entry: null, ...over });
export const page = (items: unknown[] = [], over: Record<string, unknown> = {}) => ({ items, hasMore: false, nextCursor: null, ...over });
export const receipt = (over: Record<string, unknown> = {}) => ({ gapId: 9007199254740993n, resultVersion: 0n, state: 'SUBMITTED',
  eventId, solutionId: null, replayed: false, ...over });
export function response(value: unknown, status = 200): Response {
  return new Response(encodeJson({ code: 200, message: 'ok', data: value }), { status, headers: { 'Content-Type': 'application/json' } });
}
export async function ready(next: (path: string, options: RequestInit) => Response | Promise<Response> = () => response(page())) {
  const saved = storage();
  const transport = vi.fn((path: string, options: RequestInit) => path === '/api/auth/me' ? response(me) : next(path, options));
  const session = new Session(saved, transport as unknown as typeof fetch);
  await session.refresh(); transport.mockClear();
  return { session, transport, saved };
}
