import { ApiError, encodeJson, positiveId, readJson } from '../../api';
import type { Session } from '../../session';
import type { QueryPage } from '../../types';
import { adminFailure, adminWrite, exactVersion, numericId, type WriteIntent } from '../api';
import type { Version } from '../types';
import type { EmergencyUser, EmployeeInput, EmployeeVerified, LifecycleEvent, LifecycleItem, LifecycleStatus, LifecycleUser, Proof, RestorationVerified } from './types';

const base = '/api/admin/identity/lifecycle';
export const lifecyclePaths = {
  status: `${base}/status`, emergency: `${base}/emergency`, events: `${base}/events`,
  user: (id: string) => `/api/admin/identity/users/${positiveId(id)}/lifecycle`,
  items: (id: string) => `${base}/events/${positiveId(id)}/items`,
};
export const eventStatuses = ['UNMAPPED', 'REVIEW', 'FAILED', 'OFFBOARDED', 'CONFLICT', 'IGNORED'] as const;
export function pagePath(path: string, cursor: string | null, status?: string) {
  if (status && !eventStatuses.some(value => value === status)) throw new ApiError(400);
  const params = new URLSearchParams({ limit: '20' });
  if (cursor) params.set('cursor', cursor);
  if (status) params.set('status', status);
  return `${path}?${params}`;
}
export function mappingVersion(value: Version): bigint { return value === -1 || value === -1n ? -1n : exactVersion(value); }
export function reasonText(value: string) {
  const result = value.trim();
  if (!result || result.length > 500 || /\p{Cc}/u.test(result)) throw new ApiError(400);
  return result;
}
export function employeeInput(user: LifecycleUser, connectionId: string, employeeId: string): EmployeeInput {
  if (!connectionId || !employeeId || employeeId !== employeeId.trim() || employeeId.length > 128 || /\p{Cc}/u.test(employeeId)) throw new ApiError(400);
  return { source: 'dingtalk', connectionId, employeeId, version: exactVersion(user.version), mappingVersion: mappingVersion(user.mappingVersion) };
}
const object = (value: unknown): Record<string, unknown> => {
  if (!value || typeof value !== 'object' || Array.isArray(value)) throw new ApiError(503);
  return value as Record<string, unknown>;
};
function text(value: unknown): string { if (typeof value !== 'string') throw new ApiError(503); return value; }
function bool(value: unknown): boolean { if (typeof value !== 'boolean') throw new ApiError(503); return value; }
function count(value: unknown) { try { exactVersion(value as Version); } catch { throw new ApiError(503); } }
function id(value: unknown) { try { positiveId(value); } catch { throw new ApiError(503); } }
function versions(row: Record<string, unknown>) {
  count(row.version);
  try { mappingVersion(row.mappingVersion as Version); } catch { throw new ApiError(503); }
}
function restriction(value: unknown) { if (!['CLEAR', 'OFFBOARDED', 'REVIEW'].includes(text(value))) throw new ApiError(503); }
export function checkUser(value: unknown): LifecycleUser {
  const row = object(value); id(row.userId); versions(row); bool(row.enabled); bool(row.manualEnabled); restriction(row.restriction);
  if (!['NONE', 'MANAGED', 'EMERGENCY'].includes(text(row.kind)) || row.enabled !== (row.manualEnabled && row.restriction === 'CLEAR')) throw new ApiError(503);
  if (row.kind === 'MANAGED' && (!text(row.connectionId).trim() || text(row.connectionId).length > 128)) throw new ApiError(503);
  return value as LifecycleUser;
}
export function checkStatus(value: unknown): LifecycleStatus {
  const row = object(value);
  for (const key of ['subscriptionEnabled', 'configured', 'connected', 'emergencyReady']) bool(row[key]);
  text(row.connectionId); const counts = object(row.counts);
  for (const key of ['managed', 'restricted', 'unmapped', 'review', 'failed']) count(counts[key]);
  return value as LifecycleStatus;
}
function checkEvent(value: unknown): LifecycleEvent {
  const row = object(value); id(row.id); bool(row.conflicted); text(row.eventType); text(row.bornAt); text(row.recordedAt);
  for (const key of ['restricted', 'review', 'unmapped', 'failed']) count(row[key]);
  return value as LifecycleEvent;
}
function checkItem(value: unknown): LifecycleItem {
  const row = object(value); id(row.id);
  if (row.userId != null) id(row.userId);
  if (!['OFFBOARDED', 'REVIEW', 'UNMAPPED', 'IGNORED', 'FAILED'].includes(text(row.status))) throw new ApiError(503);
  count(row.attempts); text(row.updatedAt);
  return value as LifecycleItem;
}
function checkEmergency(value: unknown): EmergencyUser {
  const row = object(value); id(row.userId); text(row.displayName); versions(row); bool(row.qualified);
  return value as EmergencyUser;
}
function checkProof(value: unknown): Proof {
  const row = object(value); versions(row);
  if (!/^[A-Za-z0-9_-]{43}$/.test(text(row.proof)) || !Number.isFinite(Date.parse(text(row.expiresAt)))) throw new ApiError(503);
  return value as Proof;
}
async function page<T>(session: Session, path: string, check: (row: unknown) => T, signal: AbortSignal): Promise<QueryPage<T>> {
  const row = object(await session.json(path, { signal }));
  if (!Array.isArray(row.items) || row.items.length > 20 || typeof row.hasMore !== 'boolean'
    || (row.nextCursor !== null && typeof row.nextCursor !== 'string') || (row.hasMore && !row.nextCursor)) throw new ApiError(503);
  return { items: row.items.map(check), hasMore: row.hasMore, nextCursor: row.nextCursor as string | null };
}
export async function lifecycleUser(session: Session, userId: string, signal: AbortSignal) {
  const result = checkUser(await session.json(lifecyclePaths.user(userId), { signal }));
  if (result.userId !== userId) throw new ApiError(503);
  return result;
}
export async function lifecycleStatus(session: Session, signal: AbortSignal) { return checkStatus(await session.json(lifecyclePaths.status, { signal })); }
export const lifecycleEvents = (session: Session, cursor: string | null, status: string, signal: AbortSignal) => page(session, pagePath(lifecyclePaths.events, cursor, status), checkEvent, signal);
export const lifecycleItems = (session: Session, eventId: string, cursor: string | null, signal: AbortSignal) => page(session, pagePath(lifecyclePaths.items(eventId), cursor), checkItem, signal);
export const emergencyUsers = (session: Session, cursor: string | null, signal: AbortSignal) => page(session, pagePath(lifecyclePaths.emergency, cursor), checkEmergency, signal);

async function verify(session: Session, path: string, body: unknown, signal: AbortSignal) {
  const response = await session.send(path, { method: 'POST', headers: { 'Content-Type': 'application/json' }, body: encodeJson(body), signal });
  if (!response.ok) throw await adminFailure(response);
  const result = await readJson<unknown>(response);
  if (signal.aborted) throw new DOMException('核验已停止', 'AbortError');
  checkProof(result);
  return object(result);
}
export async function verifyEmployee(session: Session, userId: string, input: EmployeeInput, signal: AbortSignal): Promise<EmployeeVerified> {
  const row = await verify(session, `${lifecyclePaths.user(userId)}/verification`, input, signal);
  text(row.displayName);
  if (row.connectionId !== input.connectionId || BigInt(row.version as Version) !== input.version || BigInt(row.mappingVersion as Version) !== input.mappingVersion) throw new ApiError(503);
  return row as unknown as EmployeeVerified;
}
export async function verifyRestoration(session: Session, user: LifecycleUser, signal: AbortSignal): Promise<RestorationVerified> {
  const row = await verify(session, `${lifecyclePaths.user(user.userId)}/restoration-check`, { version: exactVersion(user.version), mappingVersion: exactVersion(user.mappingVersion) }, signal);
  text(row.employeeName); id(row.departmentId); text(row.departmentName); bool(row.manualEnabled); restriction(row.restriction); count(row.eventBoundary);
  if (!Number.isInteger(row.clearanceLevel) || !Array.isArray(row.roles) || row.roles.length > 1000) throw new ApiError(503);
  for (const value of row.roles) { const role = object(value); id(role.roleId); text(role.code); text(role.name); }
  if (BigInt(row.version as Version) !== BigInt(user.version) || BigInt(row.mappingVersion as Version) !== BigInt(user.mappingVersion)) throw new ApiError(503);
  return row as unknown as RestorationVerified;
}
export async function verifyEmergency(session: Session, user: LifecycleUser, replacement: EmergencyUser | null, password: string, signal: AbortSignal) {
  if (!password || user.userId !== session.getSnapshot().me?.user.userId) throw new ApiError(400);
  const row = await verify(session, `${lifecyclePaths.emergency}/verification`, { userId: numericId(user.userId), version: exactVersion(user.version),
    replaceUserId: replacement ? numericId(replacement.userId) : null, replaceMappingVersion: replacement ? exactVersion(replacement.mappingVersion) : null, password }, signal);
  if (BigInt(row.version as Version) !== BigInt(user.version) || BigInt(row.mappingVersion as Version) !== BigInt(user.mappingVersion)) throw new ApiError(503);
  return row as unknown as Proof;
}
export const lifecycleWrites = {
  map: (userId: string, input: EmployeeInput, proof: Proof, reason: string): WriteIntent => ({ method: 'PUT', path: `${lifecyclePaths.user(userId)}/mapping`, body: { verification: input, proof: proof.proof, reason: reasonText(reason) } }),
  exit: (user: LifecycleUser, reason: string): WriteIntent => ({ method: 'DELETE', path: `${lifecyclePaths.user(user.userId)}/mapping`, body: { version: exactVersion(user.version), mappingVersion: exactVersion(user.mappingVersion), reason: reasonText(reason) } }),
  restore: (userId: string, proof: Proof, reason: string): WriteIntent => ({ method: 'POST', path: `${lifecyclePaths.user(userId)}/restore`, body: { version: exactVersion(proof.version), mappingVersion: exactVersion(proof.mappingVersion), proof: proof.proof, reason: reasonText(reason) } }),
  retry: (eventId: string, reason: string): WriteIntent => ({ method: 'POST', path: `${lifecyclePaths.events}/${positiveId(eventId)}/retry`, body: { reason: reasonText(reason) } }),
  emergency: (userId: string, proof: Proof, replacement: EmergencyUser | null, reason: string): WriteIntent => ({ method: 'POST', path: lifecyclePaths.emergency, body: { userId: numericId(userId), version: exactVersion(proof.version), mappingVersion: mappingVersion(proof.mappingVersion),
    replaceUserId: replacement ? numericId(replacement.userId) : null, replaceMappingVersion: replacement ? exactVersion(replacement.mappingVersion) : null, proof: proof.proof, reason: reasonText(reason) } }),
};

/** 原请求标识同时写入领域请求体；未知成功回执不能成为成功提示。 */
export async function lifecycleWrite<T>(session: Session, intent: WriteIntent, requestId: string, signal?: AbortSignal): Promise<T> {
  const value = await adminWrite<unknown>(session, { ...intent, body: { ...intent.body, requestId } }, requestId, signal);
  try {
    const row = object(value);
    if (intent.path.endsWith('/retry')) {
      id(row.eventId); bool(row.duplicate); bool(row.conflicted);
      for (const name of ['restricted', 'review', 'unmapped']) count(row[name]);
      if (intent.path !== `${lifecyclePaths.events}/${row.eventId}/retry`) throw new ApiError(0);
    } else {
      id(row.userId);
      if (intent.path === lifecyclePaths.emergency) {
        count(row.mappingVersion); bool(row.registered);
        if (row.registered !== true || String(row.userId) !== String(intent.body?.userId)) throw new ApiError(0);
        if ((row.replacedUserId ?? null) !== (intent.body?.replaceUserId == null ? null : String(intent.body.replaceUserId))) throw new ApiError(0);
      } else {
        versions(row); bool(row.enabled); bool(row.manualEnabled);
        if (!intent.path.startsWith(`${lifecyclePaths.user(String(row.userId))}/`)) throw new ApiError(0);
        if (intent.path.endsWith('/restore')) {
          bool(row.passwordChangeRequired);
          if (row.outcome !== (row.manualEnabled ? 'RESTORED_RELOGIN_REQUIRED' : 'RESTORED_MANUALLY_DISABLED') || row.enabled !== row.manualEnabled) throw new ApiError(0);
        } else {
          bool(row.managed); restriction(row.restriction);
          if (row.enabled !== (row.manualEnabled && row.restriction === 'CLEAR')) throw new ApiError(0);
        }
      }
    }
    return value as T;
  } catch { throw new ApiError(0); }
}
