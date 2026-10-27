import { ApiError, encodeJson, isAbort, positiveId, readJson } from '../api';
import type { Session } from '../session';
import { exactVersion, type WriteIntent } from './api';

export type Exact = number | bigint;
export interface EmbeddingProfile { profile: { profileId: string; provider: string; model: string; dimension: number; modelRevision?: string }; fingerprint: string; ready: boolean }
export interface EmbeddingStatus { state: string; activeProfileId: string | null; targetProfileId: string | null; epoch: Exact; runId: string | null;
  localEmbedding: { ready: boolean; reason: string; instanceId?: string } | null; waitingReason?: string | null }
export interface EmbeddingRun { runId: string; targetProfileId: string; state: string; version: Exact; totalCount: Exact; succeededCount: Exact; skippedCount: Exact; nextAttemptAt: string | null; errorCode: string | null }
export interface EmbeddingReceipt { operationId: string; runId: string; profileId: string; state: string; version: Exact; epoch: Exact; replayed: boolean }
export interface DeadLetter { id: string; taskId: string; versionId: string; executionGeneration: Exact; attempts: number; failureClass: string; failureCode: string; errorSummary: string; status: string; enteredAt: string; resolvedAt: string | null }
export interface DeadLetterPage { page: number; size: number; total: Exact; items: DeadLetter[] }
export interface DeadLetterFilters { status?: string; failureClass?: string; from?: string; to?: string; page: number; size: number }
const embedding = '/api/admin/embedding', deadLetters = '/api/admin/parse/dead-letters';
export const operationPaths = { profiles: `${embedding}/profiles`, status: `${embedding}/status`, rebuilds: `${embedding}/rebuilds`, deadLetters,
  run: (id: string) => `${embedding}/rebuilds/${uuid(id)}`, replay: (id: string) => `${deadLetters}/${positiveId(id)}/replay` };
export const deadLetterStates = ['OPEN', 'REPLAYED', 'RESOLVED'] as const;
export const failureClasses = ['PERMANENT', 'RETRYABLE', 'UNKNOWN'] as const;
export const workingStates = ['QUIESCING', 'REBUILDING', 'VERIFYING'];
export const uuid = (value: string) => { if (!/^[a-f\d]{8}(?:-[a-f\d]{4}){3}-[a-f\d]{12}$/i.test(value)) throw new ApiError(400); return value; };
export const exactText = (value: Exact) => exactVersion(value).toString();
export function operationReason(value: string, max: number) {
  const text = value.trim();
  if (!text || [...text].length > max || text.includes('\0')) throw new ApiError(400);
  return text;
}
export function deadLetterPath(filters: DeadLetterFilters): string {
  if (!Number.isInteger(filters.page) || filters.page < 1 || filters.page > 2147483647
      || !Number.isInteger(filters.size) || filters.size < 1 || filters.size > 100) throw new ApiError(400);
  const query = new URLSearchParams({ page: String(filters.page), size: String(filters.size) });
  for (const [name, allowed] of [['status', deadLetterStates], ['failureClass', failureClasses]] as const) {
    const value = filters[name];
    if (value) { if (!(allowed as readonly string[]).includes(value)) throw new ApiError(400); query.set(name, value); }
  }
  for (const name of ['from', 'to'] as const) {
    const value = filters[name];
    if (value) {
      if (!/^\d{4}-\d{2}-\d{2}T\d{2}:\d{2}(?::\d{2}(?:\.\d+)?)?(?:Z|[+-]\d{2}:\d{2})$/.test(value) || !Number.isFinite(Date.parse(value))) throw new ApiError(400);
      const [year, month, day] = value.slice(0, 10).split('-').map(Number), date = new Date(Date.UTC(year, month - 1, day));
      if (date.getUTCFullYear() !== year || date.getUTCMonth() !== month - 1 || date.getUTCDate() !== day) throw new ApiError(400);
      query.set(name, value);
    }
  }
  if (filters.from && filters.to && Date.parse(filters.from) >= Date.parse(filters.to)) throw new ApiError(400);
  return `${deadLetters}?${query}`;
}
export const operationWrites = {
  start: (profileId: string, epoch: Exact, reason: string): WriteIntent => {
    if (!profileId || profileId !== profileId.trim()) throw new ApiError(400);
    return { method: 'POST', path: operationPaths.rebuilds, body: { profileId, expectedEpoch: exactVersion(epoch), reason: operationReason(reason, 500) } };
  },
  resume: (runId: string, version: Exact, reason: string): WriteIntent => ({ method: 'POST', path: `${operationPaths.run(runId)}/resume`, body: { expectedVersion: exactVersion(version), reason: operationReason(reason, 500) } }),
  replay: (id: string, reason: string): WriteIntent => ({ method: 'POST', path: operationPaths.replay(id), body: { reason: operationReason(reason, 2000) } }),
};

function object(value: unknown): Record<string, unknown> { if (!value || typeof value !== 'object' || Array.isArray(value)) throw new ApiError(503); return value as Record<string, unknown>; }
function text(value: unknown): string { if (typeof value !== 'string') throw new ApiError(503); return value; }
function count(value: unknown): Exact { try { exactVersion(value as Exact); } catch { throw new ApiError(503); } return value as Exact; }
export function checkProfile(value: unknown): EmbeddingProfile {
  const row = object(value), profile = object(row.profile);
  text(profile.profileId); text(profile.model); text(profile.provider); text(row.fingerprint);
  if (!Number.isSafeInteger(profile.dimension) || typeof row.ready !== 'boolean') throw new ApiError(503);
  return value as EmbeddingProfile;
}
export function checkStatus(value: unknown): EmbeddingStatus {
  const row = object(value); text(row.state); count(row.epoch);
  for (const key of ['activeProfileId', 'targetProfileId', 'runId']) if (row[key] !== null) text(row[key]);
  if (row.runId !== null) uuid(text(row.runId));
  if (row.localEmbedding != null) { const local = object(row.localEmbedding); if (typeof local.ready !== 'boolean') throw new ApiError(503); text(local.reason); }
  return value as EmbeddingStatus;
}
export function checkRun(value: unknown): EmbeddingRun {
  const row = object(value); uuid(text(row.runId)); text(row.targetProfileId); text(row.state);
  for (const key of ['version', 'totalCount', 'succeededCount', 'skippedCount']) count(row[key]);
  for (const key of ['nextAttemptAt', 'errorCode']) if (row[key] != null) text(row[key]);
  return value as EmbeddingRun;
}
export function checkDeadLetter(value: unknown): DeadLetter {
  const row = object(value);
  for (const key of ['id', 'taskId', 'versionId']) positiveId(row[key]);
  count(row.executionGeneration);
  if (!Number.isSafeInteger(row.attempts) || Number(row.attempts) < 0) throw new ApiError(503);
  for (const key of ['failureClass', 'failureCode', 'errorSummary', 'status', 'enteredAt']) text(row[key]);
  if (row.resolvedAt != null) text(row.resolvedAt);
  return value as DeadLetter;
}
export async function profiles(session: Session, signal: AbortSignal) {
  const values = await session.json<unknown>(operationPaths.profiles, { signal });
  if (!Array.isArray(values)) throw new ApiError(503);
  return values.map(checkProfile);
}
export async function embeddingStatus(session: Session, signal: AbortSignal) { return checkStatus(await session.json(operationPaths.status, { signal })); }
export async function embeddingRun(session: Session, id: string, signal: AbortSignal) {
  const row = checkRun(await session.json(operationPaths.run(id), { signal }));
  if (row.runId !== id) throw new ApiError(503);
  return row;
}
export async function deadLetterPage(session: Session, filters: DeadLetterFilters, signal: AbortSignal): Promise<DeadLetterPage> {
  const row = object(await session.json(deadLetterPath(filters), { signal, headers: { 'X-Request-Id': crypto.randomUUID() } }));
  count(row.total);
  if (row.page !== filters.page || row.size !== filters.size || !Array.isArray(row.items)) throw new ApiError(503);
  return { page: row.page as number, size: row.size as number, total: row.total as Exact, items: row.items.map(checkDeadLetter) };
}

/** 运维错误仅按 HTTP 状态处理；无法确认成功回执时保留原逻辑写入。 */
export async function operationsWrite<T>(session: Session, intent: WriteIntent, requestId: string, signal?: AbortSignal): Promise<T> {
  const maxId = intent.path.startsWith(deadLetters) ? 256 : 128;
  if (!new RegExp(`^[\\x21-\\x7e]{1,${maxId}}$`).test(requestId)) throw new ApiError(400);
  const epoch = session.getSnapshot().epoch;
  const response = await session.send(intent.path, { method: intent.method, signal,
    headers: { 'Content-Type': 'application/json', 'X-Request-Id': requestId }, body: encodeJson(intent.body) });
  const stale = () => signal?.aborted || epoch !== session.getSnapshot().epoch;
  if (stale()) { await response.body?.cancel().catch(() => {}); throw new DOMException('请求已停止', 'AbortError'); }
  if (!response.ok) { await response.body?.cancel().catch(() => {}); throw new ApiError(response.status); }
  try {
    const value = await readJson<unknown>(response), row = object(value);
    if (stale()) throw new DOMException('请求已停止', 'AbortError');
    if (intent.path.startsWith(deadLetters)) {
      checkDeadLetter(row);
      if (operationPaths.replay(text(row.id)) !== intent.path || row.status !== 'REPLAYED') throw new ApiError(0);
    } else {
      uuid(text(row.operationId)); uuid(text(row.runId)); text(row.profileId); text(row.state); count(row.version); count(row.epoch);
      if (typeof row.replayed !== 'boolean') throw new ApiError(0);
      if (intent.path === operationPaths.rebuilds ? row.profileId !== intent.body?.profileId : `${operationPaths.run(text(row.runId))}/resume` !== intent.path) throw new ApiError(0);
    }
    return value as T;
  } catch (error) { if (isAbort(error)) throw error; throw new ApiError(0); }
}
