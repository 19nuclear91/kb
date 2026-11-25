import { ApiError, encodeJson, positiveId } from '../api';
import { numericId, type WriteIntent } from '../admin/api';
import type { AclPermission, PrincipalType, ResourceKind } from '../admin/spaceApi';
import type { Session } from '../session';

export const spacePermissions = ['view', 'download', 'edit', 'review', 'comment', 'manage'] as const;
export const documentPermissions = ['view', 'download', 'edit', 'review', 'comment'] as const;
export const assumptions = ['SELECTED_USER_AND_RESOURCE_ONLY', 'FACTS_AT_EVALUATION', 'LISTED_ACL_ONLY', 'FILE_AVAILABILITY_NOT_CHECKED'];
const reasons = ['SUBJECT_BLOCKED', 'SPACE_ARCHIVED', 'CLEARANCE_BLOCKED', 'AUTHORIZATION_NOT_GRANTED', 'VIEW_NOT_GRANTED', 'DOWNLOAD_NOT_GRANTED'];
export interface Resource { kind: 'SPACE' | 'DOCUMENT'; spaceId: string; documentId: string | null }
export interface User { userId: string; label: string }
export interface Entry { sourceKind: 'SPACE' | 'DOCUMENT'; principalType: PrincipalType; principalId: string; permission: AclPermission }
export interface Intent { type: 'SPACE_GRANT' | 'SPACE_REVOKE' | 'DOCUMENT_GRANT' | 'DOCUMENT_REVOKE'; entries: Entry[] }
export interface Source { key: string; sourceKind: 'SPACE' | 'DOCUMENT' | 'GLOBAL_MANAGEMENT'; principalType: PrincipalType | null; principalId: string | null; label: string; permission: AclPermission; origin: 'EXISTING' | 'PROPOSED' }
export interface Permission { permission: AclPermission; grantMatch: 'MATCHED' | 'NOT_MATCHED' | 'NOT_EVALUATED'; scopeCondition: 'PASSED' | 'BLOCKED' | 'NOT_EVALUATED'; reasonCodes: string[]; sourceKeys: string[] }
export interface Current { status: 'NOT_APPLICABLE' | 'NOT_EVALUATED' | 'NO_CURRENT_PUBLISHED_VERSION' | 'ASSESSED'; scope: 'CURRENT_PUBLISHED' | null; view: 'ALLOWED' | 'DENIED' | null; download: 'ALLOWED' | 'DENIED' | null; viewReasonCodes: string[]; downloadReasonCodes: string[] }
export interface Layer { subjectCondition: 'PASSED' | 'BLOCKED'; spacePermissions: Permission[]; documentPermissions: Permission[] | null; currentVersionAccess: Current; sources: Source[] }
export interface Change extends Entry { action: 'GRANT' | 'REVOKE'; outcome: 'ADDED' | 'REMOVED' | 'UNCHANGED' }
export interface Result { contract: 'AUTHORIZATION_DIAGNOSTICS_V1'; mode: 'EXPLAIN' | 'PREVIEW'; user: User; resource: Resource; evaluatedAt: string; assumptions: string[]; before: Layer; after: Layer | null; changes: Change[] }
export interface Binding { kind: ResourceKind; id: string; spaceId: string; documentId?: string | null; userId: string; intent?: Intent | null }
export interface Users { items: User[]; hasMore: boolean; nextCursor: string | null }

export const entryKey = (entry: Entry) => `${entry.sourceKind}:${({ USER: 1, ROLE: 2, DEPT: 3 })[entry.principalType]}:${entry.principalId}:${entry.permission}`;
const broken = () => new ApiError(503);
function object(value: unknown, fields: string[]): Record<string, unknown> {
  if (!value || typeof value !== 'object' || Array.isArray(value)) throw broken();
  const row = value as Record<string, unknown>;
  if (Object.keys(row).length !== fields.length || fields.some(field => !Object.hasOwn(row, field))) throw broken();
  return row;
}
function text(value: unknown): string { if (typeof value !== 'string') throw broken(); return value; }
function id(value: unknown): string { if (typeof value !== 'string') throw broken(); try { return positiveId(value); } catch { throw broken(); } }
function one<const T extends readonly string[]>(value: unknown, values: T): T[number] { if (!values.includes(value as string)) throw broken(); return value as T[number]; }
function array<T>(value: unknown, max: number, parse: (value: unknown) => T): T[] { if (!Array.isArray(value) || value.length > max) throw broken(); return value.map(parse); }
function unique(values: string[]) { if (new Set(values).size !== values.length) throw broken(); return values; }
function same(left: unknown, right: unknown) { if (encodeJson(left) !== encodeJson(right)) throw broken(); }
function resource(value: unknown, expected: Resource): Resource {
  const row = object(value, ['kind', 'spaceId', 'documentId']);
  const result = { kind: one(row.kind, ['SPACE', 'DOCUMENT']), spaceId: id(row.spaceId), documentId: row.documentId === null ? null : id(row.documentId) };
  same(result, expected); return result;
}
function user(value: unknown): User { const row = object(value, ['userId', 'label']); return { userId: id(row.userId), label: text(row.label) }; }
function source(value: unknown): Source {
  const row = object(value, ['key', 'sourceKind', 'principalType', 'principalId', 'label', 'permission', 'origin']);
  const sourceKind = one(row.sourceKind, ['SPACE', 'DOCUMENT', 'GLOBAL_MANAGEMENT']);
  const result: Source = { key: text(row.key), sourceKind, principalType: row.principalType === null ? null : one(row.principalType, ['USER', 'ROLE', 'DEPT']),
    principalId: row.principalId === null ? null : id(row.principalId), label: text(row.label), permission: one(row.permission, spacePermissions), origin: one(row.origin, ['EXISTING', 'PROPOSED']) };
  if (sourceKind === 'GLOBAL_MANAGEMENT') {
    if (result.key !== 'GLOBAL_MANAGEMENT' || result.principalId !== null || result.principalType !== null || result.permission !== 'manage' || result.origin !== 'EXISTING') throw broken();
  } else {
    if (!result.principalType || !result.principalId || result.key !== entryKey(result as Entry) || sourceKind === 'DOCUMENT' && !['view', 'download', 'edit', 'comment'].includes(result.permission)) throw broken();
  }
  return result;
}
function permission(value: unknown, bit: AclPermission, sources: Source[], document: boolean, passed: boolean): Permission {
  const row = object(value, ['permission', 'grantMatch', 'scopeCondition', 'reasonCodes', 'sourceKeys']);
  if (row.permission !== bit) throw broken();
  const result: Permission = { permission: bit, grantMatch: one(row.grantMatch, ['MATCHED', 'NOT_MATCHED', 'NOT_EVALUATED']), scopeCondition: one(row.scopeCondition, ['PASSED', 'BLOCKED', 'NOT_EVALUATED']),
    reasonCodes: unique(array(row.reasonCodes, 6, value => one(value, reasons))), sourceKeys: unique(array(row.sourceKeys, 1000, text)) };
  const expected = sources.filter(source => source.permission === bit && (document || source.sourceKind !== 'DOCUMENT')).map(source => source.key).sort();
  same([...result.sourceKeys].sort(), expected);
  if (!passed) {
    if (result.grantMatch !== 'NOT_EVALUATED' || result.scopeCondition !== 'NOT_EVALUATED' || result.sourceKeys.length) throw broken();
    same(result.reasonCodes, ['SUBJECT_BLOCKED']);
  } else {
    if (result.grantMatch !== (expected.length ? 'MATCHED' : 'NOT_MATCHED') || result.scopeCondition === 'NOT_EVALUATED') throw broken();
    if (result.reasonCodes.includes('SUBJECT_BLOCKED') || result.reasonCodes.includes('VIEW_NOT_GRANTED') || result.reasonCodes.includes('DOWNLOAD_NOT_GRANTED')) throw broken();
    if (result.reasonCodes.includes('AUTHORIZATION_NOT_GRANTED') !== !expected.length) throw broken();
    if (!document && result.reasonCodes.includes('CLEARANCE_BLOCKED') || bit === 'manage' && result.scopeCondition !== 'PASSED') throw broken();
    const blocked = result.reasonCodes.some(code => code === 'SPACE_ARCHIVED' || code === 'CLEARANCE_BLOCKED');
    if (blocked !== (result.scopeCondition === 'BLOCKED')) throw broken();
  }
  return result;
}
function layer(value: unknown, hasDocument: boolean, userId: string): Layer {
  const row = object(value, ['subjectCondition', 'spacePermissions', 'documentPermissions', 'currentVersionAccess', 'sources']);
  const subjectCondition = one(row.subjectCondition, ['PASSED', 'BLOCKED']), passed = subjectCondition === 'PASSED';
  const sources = array(row.sources, 1000, source); unique(sources.map(source => source.key));
  if (sources.some(source => source.principalType === 'USER' && source.principalId !== userId)) throw broken();
  if (!passed && sources.length || !hasDocument && sources.some(source => source.sourceKind === 'DOCUMENT')) throw broken();
  const parsePermissions = (value: unknown, bits: readonly AclPermission[], document: boolean) => {
    const values = array(value, bits.length, value => value); if (values.length !== bits.length) throw broken();
    const indexed = values.map(value => { if (!value || typeof value !== 'object') throw broken(); return value as Record<string, unknown>; });
    unique(indexed.map(row => text(row.permission)));
    return bits.map(bit => permission(indexed.find(row => row.permission === bit), bit, sources, document, passed));
  };
  const space = parsePermissions(row.spacePermissions, spacePermissions, false);
  const document = hasDocument ? parsePermissions(row.documentPermissions, documentPermissions, true) : null;
  if (!hasDocument && row.documentPermissions !== null) throw broken();
  const current = object(row.currentVersionAccess, ['status', 'scope', 'view', 'download', 'viewReasonCodes', 'downloadReasonCodes']);
  const access: Current = { status: one(current.status, ['NOT_APPLICABLE', 'NOT_EVALUATED', 'NO_CURRENT_PUBLISHED_VERSION', 'ASSESSED']),
    scope: current.scope === null ? null : one(current.scope, ['CURRENT_PUBLISHED']), view: current.view === null ? null : one(current.view, ['ALLOWED', 'DENIED']),
    download: current.download === null ? null : one(current.download, ['ALLOWED', 'DENIED']), viewReasonCodes: unique(array(current.viewReasonCodes, 4, value => one(value, reasons))),
    downloadReasonCodes: unique(array(current.downloadReasonCodes, 4, value => one(value, reasons))) };
  if (access.scope !== (hasDocument ? 'CURRENT_PUBLISHED' : null)) throw broken();
  if (!hasDocument ? access.status !== 'NOT_APPLICABLE' : !passed ? access.status !== 'NOT_EVALUATED' : !['ASSESSED', 'NO_CURRENT_PUBLISHED_VERSION'].includes(access.status)) throw broken();
  if (access.status !== 'ASSESSED') {
    if (access.view !== null || access.download !== null || access.viewReasonCodes.length || access.downloadReasonCodes.length) throw broken();
  } else {
    const view = document![0], download = document![1];
    const viewReasons = view.reasonCodes.filter(reason => reason !== 'AUTHORIZATION_NOT_GRANTED');
    if (view.grantMatch !== 'MATCHED') viewReasons.push('VIEW_NOT_GRANTED');
    const downloadReasons = [...viewReasons]; if (download.grantMatch !== 'MATCHED') downloadReasons.push('DOWNLOAD_NOT_GRANTED');
    same([...access.viewReasonCodes].sort(), viewReasons.sort()); same([...access.downloadReasonCodes].sort(), downloadReasons.sort());
    if (access.view !== (viewReasons.length ? 'DENIED' : 'ALLOWED') || access.download !== (downloadReasons.length ? 'DENIED' : 'ALLOWED')) throw broken();
  }
  if (passed) {
    const archived = space[0].reasonCodes.includes('SPACE_ARCHIVED');
    if (space.slice(0, 5).some(row => row.reasonCodes.includes('SPACE_ARCHIVED') !== archived)) throw broken();
    if (document?.some(row => row.reasonCodes.includes('SPACE_ARCHIVED') !== archived || row.reasonCodes.includes('CLEARANCE_BLOCKED') !== document[0].reasonCodes.includes('CLEARANCE_BLOCKED'))) throw broken();
  }
  return { subjectCondition, spacePermissions: space, documentPermissions: document, currentVersionAccess: access, sources };
}
export function expectedResource(binding: Omit<Binding, 'userId'>): Resource {
  return { kind: binding.kind === 'spaces' ? 'SPACE' : 'DOCUMENT', spaceId: positiveId(binding.spaceId), documentId: binding.kind === 'documents' ? positiveId(binding.id) : binding.documentId ?? null };
}
export function decodeResult(value: unknown, binding: Binding): Result {
  const row = object(value, ['contract', 'mode', 'user', 'resource', 'evaluatedAt', 'assumptions', 'before', 'after', 'changes']);
  if (row.contract !== 'AUTHORIZATION_DIAGNOSTICS_V1' || row.mode !== (binding.intent ? 'PREVIEW' : 'EXPLAIN')) throw broken();
  const target = user(row.user); if (target.userId !== binding.userId) throw broken();
  const resolved = resource(row.resource, expectedResource(binding)), evaluatedAt = text(row.evaluatedAt);
  if (!/^\d{4}-\d{2}-\d{2}T.*(?:Z|[+-]\d{2}:\d{2})$/.test(evaluatedAt) || !Number.isFinite(Date.parse(evaluatedAt))) throw broken();
  same(row.assumptions, assumptions);
  const before = layer(row.before, resolved.documentId !== null, target.userId), after = binding.intent ? layer(row.after, resolved.documentId !== null, target.userId) : null;
  if (!binding.intent && row.after !== null) throw broken();
  const changes: Change[] = array(row.changes, 300, value => {
    const entry = object(value, ['sourceKind', 'principalType', 'principalId', 'permission', 'action', 'outcome']);
    return { sourceKind: one(entry.sourceKind, ['SPACE', 'DOCUMENT']), principalType: one(entry.principalType, ['USER', 'ROLE', 'DEPT']), principalId: id(entry.principalId),
      permission: one(entry.permission, spacePermissions), action: one(entry.action, ['GRANT', 'REVOKE']), outcome: one(entry.outcome, ['ADDED', 'REMOVED', 'UNCHANGED']) };
  });
  unique(changes.map(entryKey));
  const expected = binding.intent?.entries.map(entryKey).sort() ?? []; same(changes.map(entryKey).sort(), expected);
  if (changes.some(change => change.action !== (binding.intent!.type.endsWith('GRANT') ? 'GRANT' : 'REVOKE') || change.action === 'GRANT' && change.outcome === 'REMOVED' || change.action === 'REVOKE' && change.outcome === 'ADDED')) throw broken();
  if (before.sources.some(source => source.origin !== 'EXISTING')) throw broken();
  if (after) {
    same(before.subjectCondition, after.subjectCondition);
    same(before.spacePermissions.map(row => row.scopeCondition), after.spacePermissions.map(row => row.scopeCondition));
    same(before.documentPermissions?.map(row => row.scopeCondition), after.documentPermissions?.map(row => row.scopeCondition));
    const conditions = (rows: Permission[] | null) => rows?.map(row => row.reasonCodes.filter(reason => reason !== 'AUTHORIZATION_NOT_GRANTED').sort());
    same(conditions(before.spacePermissions), conditions(after.spacePermissions));
    same(conditions(before.documentPermissions), conditions(after.documentPermissions));
    same(before.currentVersionAccess.status, after.currentVersionAccess.status);
    const old = new Map(before.sources.map(source => [source.key, source])), next = new Map(after.sources.map(source => [source.key, source]));
    if (new Set([...old.keys(), ...next.keys()]).size > 1000) throw broken();
    // 仅由已披露命中来源及目标本人推断成员关系，不要求展开无关角色或部门。
    const matchedPrincipals = new Set([...old.values(), ...next.values()].map(source => `${source.principalType}:${source.principalId}`));
    if (before.subjectCondition === 'PASSED') matchedPrincipals.add(`USER:${target.userId}`);
    for (const change of changes) {
      const key = entryKey(change), existed = old.has(key), remains = next.has(key);
      if (change.outcome === 'ADDED' && existed || change.outcome === 'REMOVED' && remains
        || change.action === 'REVOKE' && change.outcome === 'UNCHANGED' && (existed || remains)) throw broken();
      if (matchedPrincipals.has(`${change.principalType}:${change.principalId}`)) {
        const granting = change.action === 'GRANT';
        if (change.outcome !== (granting ? existed ? 'UNCHANGED' : 'ADDED' : existed ? 'REMOVED' : 'UNCHANGED') || remains !== granting) throw broken();
      }
    }
    for (const source of before.sources) {
      const change = changes.find(change => entryKey(change) === source.key);
      if (change?.outcome === 'REMOVED') { if (next.has(source.key)) throw broken(); }
      else same(source, next.get(source.key));
    }
    for (const source of after.sources) {
      if (!old.has(source.key) && (source.origin !== 'PROPOSED' || !changes.some(change => entryKey(change) === source.key && change.outcome === 'ADDED'))) throw broken();
    }
  }
  return { contract: 'AUTHORIZATION_DIAGNOSTICS_V1', mode: binding.intent ? 'PREVIEW' : 'EXPLAIN', user: target, resource: resolved, evaluatedAt, assumptions, before, after, changes };
}

/** 读取既有写意图的完整载荷，保持批量笛卡尔积和显式撤销的区别。 */
export function fromWrite(kind: ResourceKind, write: WriteIntent): Intent {
  const body = write.body as Record<string, unknown>, sourceKind = kind === 'spaces' ? 'SPACE' : 'DOCUMENT';
  const entry = (value: unknown, permission?: unknown): Entry => {
    const row = value as Record<string, unknown>;
    return { sourceKind, principalType: one(row.principalType, ['USER', 'ROLE', 'DEPT']), principalId: positiveId(row.principalId), permission: one(permission ?? row.permission, spacePermissions) };
  };
  let entries: Entry[], grant = write.method !== 'DELETE' && !write.path.endsWith('/batch-revoke');
  if (write.path.endsWith('/batch')) entries = (body.principals as unknown[]).flatMap(principal => (body.permissions as unknown[]).map(permission => entry(principal, permission)));
  else if (write.path.endsWith('/batch-revoke')) entries = (body.items as unknown[]).map(value => entry(value));
  else entries = [entry(body)];
  return { type: `${sourceKind}_${grant ? 'GRANT' : 'REVOKE'}`, entries: entries.sort((a, b) => entryKey(a).localeCompare(entryKey(b))) };
}
function intentBody(intent: Intent) {
  const entry = (row: Entry) => ({ principalType: row.principalType, principalId: numericId(row.principalId), permission: row.permission });
  if (intent.type === 'SPACE_GRANT') {
    const principals = [...new Map(intent.entries.map(row => [`${row.principalType}:${row.principalId}`, { principalType: row.principalType, principalId: numericId(row.principalId) }])).values()];
    const permissions = [...new Set(intent.entries.map(row => row.permission))];
    if (principals.length * permissions.length !== intent.entries.length || principals.length > 50 || !principals.length) throw new ApiError(400);
    return { type: intent.type, principals, permissions };
  }
  if (intent.type === 'SPACE_REVOKE') return { type: intent.type, items: intent.entries.map(entry) };
  if (intent.entries.length !== 1) throw new ApiError(400);
  return { type: intent.type, ...entry(intent.entries[0]) };
}
async function call(session: Session, kind: ResourceKind, id: string, action: string, body: unknown, signal?: AbortSignal) {
  const epoch = session.getSnapshot().epoch;
  const current = () => { if (signal?.aborted || epoch !== session.getSnapshot().epoch) throw new DOMException('请求已停止', 'AbortError'); };
  const response = await session.send(`/api/admin/${kind}/${positiveId(id)}/authorization-diagnostics/${action}`,
    { method: 'POST', headers: { 'Content-Type': 'application/json' }, body: encodeJson(body), signal }, false, false);
  try { current(); } catch (error) { await response.body?.cancel(); throw error; }
  if (!response.headers.get('Content-Type')?.includes('application/json') || !response.body) { await response.body?.cancel(); throw new ApiError(response.ok ? 503 : response.status); }
  const reader = response.body.getReader(), chunks: Uint8Array[] = []; let size = 0;
  try {
    for (;;) { const next = await reader.read(); current(); if (next.done) break; size += next.value.byteLength; if (size > 524288) { await reader.cancel(); throw broken(); } chunks.push(next.value); }
  } finally { reader.releaseLock(); }
  const bytes = new Uint8Array(size); let offset = 0; chunks.forEach(chunk => { bytes.set(chunk, offset); offset += chunk.length; });
  let value: Record<string, unknown>;
  try { value = JSON.parse(new TextDecoder('utf-8', { fatal: true }).decode(bytes)); } catch { throw broken(); }
  current();
  if (!response.ok) {
    const error = new ApiError(response.status);
    if (value && value.code === 500 && value.data === null) {
      if (response.status === 400 && value.reasonCode === 'DIAGNOSTIC_LIMIT_EXCEEDED') error.message = '诊断范围超过限制，请缩小授权草稿。';
      if (response.status === 413 && value.reasonCode === 'PAYLOAD_TOO_LARGE') error.message = '诊断请求过大。';
    }
    throw error;
  }
  if (!value || value.code !== 200) throw broken();
  return value.data;
}
export const diagnosticApi = {
  async diagnose(session: Session, binding: Binding, signal?: AbortSignal): Promise<Result> {
    const body = { userId: numericId(binding.userId), ...(binding.kind === 'spaces' && binding.documentId ? { documentId: numericId(binding.documentId) } : {}), ...(binding.intent ? { intent: intentBody(binding.intent) } : {}) };
    return decodeResult(await call(session, binding.kind, binding.id, binding.intent ? 'preview' : 'explain', body, signal), binding);
  },
  async users(session: Session, binding: Omit<Binding, 'userId'>, q: string, cursor: string | null, signal?: AbortSignal): Promise<Users> {
    if ([...q].length > 128) throw new ApiError(400);
    const row = object(await call(session, binding.kind, binding.id, 'users/search', { q, limit: 20, ...(cursor ? { cursor } : {}) }, signal), ['contract', 'resource', 'items', 'hasMore', 'nextCursor']);
    if (row.contract !== 'AUTHORIZATION_DIAGNOSTIC_USERS_V1') throw broken();
    resource(row.resource, expectedResource({ ...binding, documentId: null }));
    const items = array(row.items, 20, user); unique(items.map(row => row.userId));
    if (items.some((row, index) => index > 0 && BigInt(row.userId) <= BigInt(items[index - 1].userId))) throw broken();
    if (typeof row.hasMore !== 'boolean' || row.hasMore && (!row.nextCursor || !items.length) || !row.hasMore && row.nextCursor !== null) throw broken();
    const nextCursor = row.nextCursor === null ? null : text(row.nextCursor); if (nextCursor && nextCursor.length > 2731) throw broken();
    return { items, hasMore: row.hasMore, nextCursor };
  },
};
