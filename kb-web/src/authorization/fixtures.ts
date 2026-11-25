import { assumptions, spacePermissions, documentPermissions, entryKey, type Binding, type Layer, type Result, type Source, type Permission } from './api';

/** 独立组件回归使用的最小合成契约。 */
export function diagnosticFixture(binding: Binding, options: { sources?: Source[]; archived?: boolean; clearanceBlocked?: boolean; current?: boolean } = {}): Result {
  const document = binding.kind === 'documents' || !!binding.documentId;
  const beforeSources = options.sources ?? [], afterSources = new Map(beforeSources.map(source => [source.key, source]));
  const matches = new Set(beforeSources.map(source => `${source.principalType}:${source.principalId}`)); matches.add(`USER:${binding.userId}`);
  const changes: Result['changes'] = (binding.intent?.entries ?? []).map(entry => {
    const key = entryKey(entry), exists = afterSources.has(key), grant = binding.intent!.type.endsWith('GRANT');
    if (grant && !exists && matches.has(`${entry.principalType}:${entry.principalId}`)) afterSources.set(key, { ...entry, key, label: entry.principalId, origin: 'PROPOSED' });
    if (!grant) afterSources.delete(key);
    return { ...entry, action: grant ? 'GRANT' : 'REVOKE', outcome: grant ? exists ? 'UNCHANGED' : 'ADDED' : exists ? 'REMOVED' : 'UNCHANGED' };
  });
  const layer = (sources: Source[]): Layer => {
    const permission = (bit: Permission['permission'], forDocument: boolean): Permission => {
      const sourceKeys = sources.filter(source => source.permission === bit && (forDocument || source.sourceKind !== 'DOCUMENT')).map(source => source.key);
      const conditions = [...(options.archived && bit !== 'manage' ? ['SPACE_ARCHIVED'] : []), ...(forDocument && options.clearanceBlocked ? ['CLEARANCE_BLOCKED'] : [])];
      return { permission: bit, grantMatch: sourceKeys.length ? 'MATCHED' : 'NOT_MATCHED', scopeCondition: conditions.length ? 'BLOCKED' : 'PASSED',
        reasonCodes: [...conditions, ...(!sourceKeys.length ? ['AUTHORIZATION_NOT_GRANTED'] : [])], sourceKeys };
    };
    const space = spacePermissions.map(bit => permission(bit, false)), doc = document ? documentPermissions.map(bit => permission(bit, true)) : null;
    const current: Layer['currentVersionAccess'] = { status: document ? 'NO_CURRENT_PUBLISHED_VERSION' : 'NOT_APPLICABLE', scope: document ? 'CURRENT_PUBLISHED' : null,
      view: null, download: null, viewReasonCodes: [], downloadReasonCodes: [] };
    if (doc && options.current) {
      const viewReasons = [...(options.archived ? ['SPACE_ARCHIVED'] : []), ...(options.clearanceBlocked ? ['CLEARANCE_BLOCKED'] : []),
        ...(doc[0].grantMatch === 'NOT_MATCHED' ? ['VIEW_NOT_GRANTED'] : [])];
      const downloadReasons = [...viewReasons, ...(doc[1].grantMatch === 'NOT_MATCHED' ? ['DOWNLOAD_NOT_GRANTED'] : [])];
      Object.assign(current, { status: 'ASSESSED', view: viewReasons.length ? 'DENIED' : 'ALLOWED', download: downloadReasons.length ? 'DENIED' : 'ALLOWED', viewReasonCodes: viewReasons, downloadReasonCodes: downloadReasons });
    }
    return { subjectCondition: 'PASSED', sources, spacePermissions: space, documentPermissions: doc, currentVersionAccess: current };
  };
  return { contract: 'AUTHORIZATION_DIAGNOSTICS_V1', mode: binding.intent ? 'PREVIEW' : 'EXPLAIN', user: { userId: binding.userId, label: '<img src=x onerror=alert(1)>' },
    resource: { kind: binding.kind === 'spaces' ? 'SPACE' : 'DOCUMENT', spaceId: binding.spaceId, documentId: binding.kind === 'documents' ? binding.id : binding.documentId ?? null }, evaluatedAt: '2026-11-25T08:00:00Z', assumptions,
    before: layer(beforeSources), after: binding.intent ? layer([...afterSources.values()]) : null, changes };
}
