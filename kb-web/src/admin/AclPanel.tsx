import { memo, useEffect, useState } from 'react';
import { Alert, Button } from 'antd';
import { ApiError, decodeJson, encodeJson } from '../api';
import { useSession } from '../SessionProvider';
import { useDocumentOperations } from '../documents/OperationContext';
import { perm, useAdminAccess } from './access';
import { useAdminResource } from './hooks';
import { adminWrite } from './api';
import { MultiPrincipalPicker } from './MultiPrincipalPicker';
import { PrincipalPicker } from './PrincipalPicker';
import { spacePaths, spaceWrites, type Acl, type AclPermission, type BatchGrantResult, type BatchRevokeItem, type BatchRevokeResult, type GrantResult, type Principal, type ResourceKind } from './spaceApi';
import { fieldValue, ResourceStatus, ResourceWriteDialog, useResourceNotice, useResourceReceipt, useResourceWriteBindings } from './spaceUi';

type AclPanelProps = {
  kind: ResourceKind; resourceId: string; archived?: boolean; onChanged: () => void; onData?: (items: Acl[] | undefined) => void;
};
type BatchDraft = { principals: Principal[]; permissions: AclPermission[]; rejected?: string; unsupported?: boolean };
type RevokeDraft = { keys: string[]; rejected?: string; unsupported?: boolean };
const aclKey = (item: BatchRevokeItem) => `${item.principalType}:${item.principalId}:${item.permission}`;

const batchWrite: typeof adminWrite = async (session, intent, requestId, signal) => {
  try { return await adminWrite(session, intent, requestId, signal); }
  catch (error) {
    if (error instanceof ApiError && (error.status === 404 || error.status === 405)) error.message = '当前服务不支持批量授予';
    throw error;
  }
};

const revokeBatchWrite: typeof adminWrite = async (session, intent, requestId, signal) => {
  try { return await adminWrite(session, intent, requestId, signal); }
  catch (error) {
    if (error instanceof ApiError && (error.status === 404 || error.status === 405)) error.message = '当前服务不支持批量撤销';
    throw error;
  }
};

// 选择批量条目只改变复选框，不必重渲染列表中每个撤销按钮。
const RevokeButton = memo(function RevokeButton({ acl, disabled, onRevoke }: {
  acl: Acl; disabled: boolean; onRevoke: (acl: Acl) => void;
}) {
  return <Button aria-label={`撤销 ${acl.principalName} ${acl.permission}`} disabled={disabled} onClick={() => onRevoke(acl)}>撤销</Button>;
});

export function AclPanel(props: AclPanelProps) {
  const { state } = useSession();
  return <AclPanelContent key={`${state.epoch}:${props.kind}:${props.resourceId}`} {...props} />;
}

function AclPanelContent({ kind, resourceId, archived = false, onChanged, onData }: AclPanelProps) {
  const { session, state } = useSession(), access = useAdminAccess(), { state: documentState } = useDocumentOperations();
  const query = useAdminResource<Acl[]>(signal => session.json(spacePaths.acl(kind, resourceId), { signal }), `${kind}:${resourceId}`);
  const [dialog, setDialog] = useState<'grant' | 'revoke-batch' | Acl | null>(null), [principal, setPrincipal] = useState<Principal | null>(null);
  const [receipt, setReceipt] = useResourceReceipt(`acl:${kind}:${resourceId}`);
  const [batchJson, setBatchJson] = useResourceReceipt(`acl-batch:${kind}:${resourceId}`);
  const batchReceipt = batchJson ? decodeJson<BatchGrantResult>(batchJson) : null;
  const [revokeJson, setRevokeJson] = useResourceReceipt(`acl-revoke:${kind}:${resourceId}`);
  const revokeReceipt = revokeJson ? decodeJson<BatchRevokeResult>(revokeJson) : null;
  const batchBindings = useResourceWriteBindings(), batchScope = `spaces:${resourceId}:grant-batch`;
  const pendingBatch = kind === 'spaces' && batchBindings.has(`${state.epoch}|${batchScope}`);
  const revokeScope = `spaces:${resourceId}:revoke-batch`;
  const pendingRevoke = kind === 'spaces' && batchBindings.has(`${state.epoch}|${revokeScope}`);
  const [revokeDraftJson, setRevokeDraftJson] = useResourceNotice(`acl-revoke-draft:${kind}:${resourceId}`);
  const revokeDraft: RevokeDraft = revokeDraftJson ? decodeJson<RevokeDraft>(revokeDraftJson) : { keys: [] };
  const saveRevokeDraft = (next: RevokeDraft) => setRevokeDraftJson(encodeJson(next));
  // 403 重核会卸载页面；草稿按身份和空间保存在当前内存，恢复后仍可修改重提。
  const [draftJson, setDraftJson] = useResourceNotice(`acl-batch-draft:${kind}:${resourceId}`);
  const draft: BatchDraft = draftJson ? decodeJson<BatchDraft>(draftJson) : { principals: [], permissions: ['view'] };
  const { principals, permissions, unsupported: batchUnsupported } = draft;
  const saveDraft = (next: BatchDraft) => setDraftJson(encodeJson(next));
  const setPrincipals = (next: Principal[]) => saveDraft({ ...draft, principals: next });
  const setPermissions = (next: AclPermission[]) => saveDraft({ ...draft, permissions: next });
  const manage = kind === 'spaces' && access.has(perm.grantManage);
  const selectedPermissions = permissions.filter(permission => permission !== 'manage' || manage);
  const total = principals.length * selectedPermissions.length;
  const refresh = () => query.reload();
  const confirmed = query.loading || query.error ? undefined : query.data;
  const selected = (query.data ?? []).filter(item => (item.permission !== 'manage' || manage) && revokeDraft.keys.includes(aclKey(item)));
  const selectedManage = selected.filter(item => item.permission === 'manage');
  const ownManage = selectedManage.some(item => item.principalType === 'USER' && item.principalId === state.me?.user.userId);
  const manageRows = (query.data ?? []).filter(item => item.permission === 'manage');
  const allManage = manageRows.length > 0 && selectedManage.length === manageRows.length;
  useEffect(() => {
    if (kind !== 'spaces' || !confirmed) return;
    // 只在成功重读后移除消失的键；资格重核和读取失败不能清空草稿。
    const available = new Set(confirmed.map(aclKey));
    const keys = revokeDraft.keys.filter(key => available.has(key));
    if (keys.length !== revokeDraft.keys.length) saveRevokeDraft({ ...revokeDraft, keys });
  }, [kind, confirmed, revokeDraft, saveRevokeDraft]);
  useEffect(() => { onData?.(confirmed); }, [confirmed, onData]);
  // 父面板只接收当前已经确认的例外数量，重读或失败时立即作废旧数量。
  return <section className="panel"><h2>{kind === 'spaces' ? '空间授权' : '文档例外授权'}</h2>
    {kind === 'documents' && <p>例外授权只扩权，不收紧空间授权。{archived && '所属空间已归档，取消归档前例外不会使文档可见。'}</p>}
    <ResourceStatus {...query} retry={query.reload} />{receipt && <Alert type="success" title={receipt} />}
    {batchReceipt && <div>
      <Alert type="success" title={`新写入 ${batchReceipt.grantedCount} 条，已存在 ${batchReceipt.alreadyGrantedCount} 条${batchReceipt.replayed ? '；已重放首次结果' : ''}`} />
      {batchReceipt.clearanceBlockedPrincipalCount > 0 && <p>有 {String(batchReceipt.clearanceBlockedPrincipalCount)} 个主体因密级当前不能看到受限文档。授权不修改主体密级。</p>}
      <ul aria-label="批量授予明细">{batchReceipt.items.map(item => <li key={`${item.principalType}:${item.principalId}:${item.permission}`}>
        {item.principalType} · {item.principalId} · {item.permission} · {item.alreadyGranted ? '已存在' : '新增'}
      </li>)}</ul>
    </div>}
    {revokeReceipt && <div>
      <Alert type="success" title={`已移除 ${revokeReceipt.removedCount} 条，原本不存在 ${revokeReceipt.absentCount} 条${revokeReceipt.replayed ? '；已重放首次结果' : ''}`} />
      <ul aria-label="批量撤销明细">{revokeReceipt.items.map(item => <li key={aclKey(item)}>
        {item.principalType} · {item.principalId} · {item.permission} · {item.removed ? '已移除' : '原本不存在'}
      </li>)}</ul>
    </div>}
    {draft.rejected && dialog !== 'grant' && <Alert type="warning" title={draft.rejected} />}
    {pendingBatch && dialog !== 'grant' && <Alert type="warning" title="有批量授权结果待核对，请打开授予面板继续核对。" />}
    {revokeDraft.rejected && dialog !== 'revoke-batch' && <Alert type="warning" title={revokeDraft.rejected} />}
    {pendingRevoke && dialog !== 'revoke-batch' && <Alert type="warning" title="有批量撤销结果待核对，请打开批量撤销面板继续核对。" />}
    {!query.loading && !query.error && query.data && <><Button disabled={documentState.exclusive || batchUnsupported} onClick={() => { setPrincipal(null); setDialog('grant'); if (kind === 'spaces') saveDraft({ ...draft, rejected: undefined }); }}>授予授权</Button>
      {kind === 'spaces' && <div><span aria-label="已选撤销条数">已选 {selected.length} 条授权</span>
        <Button danger disabled={documentState.exclusive || revokeDraft.unsupported || (!selected.length && !pendingRevoke)}
          onClick={() => { setDialog('revoke-batch'); saveRevokeDraft({ ...revokeDraft, rejected: undefined }); }}>批量撤销</Button></div>}
      <ul className="admin-list" aria-label="授权列表">{query.data.map(acl => <li key={acl.id}>
        {kind === 'spaces' && (acl.permission !== 'manage' || manage) && <input type="checkbox"
          aria-label={`选择撤销 ${acl.principalType} ${acl.principalName} ${acl.permission}`} checked={revokeDraft.keys.includes(aclKey(acl))}
          disabled={documentState.exclusive} onChange={event => saveRevokeDraft({ ...revokeDraft,
            keys: event.target.checked ? [...revokeDraft.keys, aclKey(acl)] : revokeDraft.keys.filter(key => key !== aclKey(acl)) })} />}
        <span>{acl.principalType} · {acl.principalName} · {acl.permission}</span>
        {(acl.permission !== 'manage' || manage) && <RevokeButton acl={acl} disabled={documentState.exclusive} onRevoke={setDialog} />}</li>)}</ul></>}
    {dialog === 'revoke-batch' && kind === 'spaces' && <ResourceWriteDialog<BatchRevokeResult> title="批量撤销空间授权" scope={revokeScope} bindings={batchBindings}
      onClose={() => setDialog(null)} onRefresh={refresh} execute={revokeBatchWrite}
      disabled={selected.length === 0 || selected.length > 300 || query.loading || !!query.error || revokeDraft.unsupported}
      retryDisabled={query.loading || !!query.error || !!revokeDraft.unsupported}
      build={() => spaceWrites.revokeBatch(resourceId, selected)}
      onRejected={error => { saveRevokeDraft({ ...revokeDraft, rejected: error.message, unsupported: error.status === 404 || error.status === 405 }); if (error.status === 409) refresh(); }}
      onDone={result => { setReceipt(''); setBatchJson(''); setRevokeJson(encodeJson(result)); setRevokeDraftJson(''); setDialog(null); refresh(); onChanged(); }}>
      <p aria-label="批量撤销摘要">共 {selected.length} 条授权，其中 {selectedManage.length} 条 manage。</p>
      {selected.length > 300 && <Alert type="warning" title="条目上限为 300，请减少选择后提交。" />}
      {ownManage && <p aria-label="本人直接 manage 提示">包含本人 USER 类型的直接 manage，撤销后本人可能失去该空间管理资格。</p>}
      {allManage && <p aria-label="全部直接 manage 提示">撤销后该空间将没有直接 manage 授权，只能由持有跨空间管理权限的人员继续管理。</p>}
      <p>仅撤销所选条目；原本不存在的条目同样视为成功。提示仅依据直接授权，不推断角色或部门带来的资格。</p>
    </ResourceWriteDialog>}
    {dialog === 'grant' && kind === 'spaces' && <ResourceWriteDialog<BatchGrantResult> title="批量授予空间授权" scope={batchScope} bindings={batchBindings}
      onClose={() => setDialog(null)} onRefresh={refresh} execute={batchWrite}
      disabled={total === 0 || principals.length > 50 || query.loading || !!query.error || batchUnsupported}
      retryDisabled={query.loading || !!query.error || batchUnsupported}
      build={() => spaceWrites.grantBatch(resourceId, principals, selectedPermissions)}
      onRejected={error => { saveDraft({ ...draft, rejected: error.message, unsupported: error.status === 404 || error.status === 405 }); if (error.status === 409) refresh(); }}
      onDone={result => { setReceipt(''); setRevokeJson(''); setBatchJson(encodeJson(result)); setDialog(null); setDraftJson(''); refresh(); onChanged(); }}>
      <MultiPrincipalPicker resourceId={resourceId} selected={principals} onChange={setPrincipals} />
      <fieldset aria-label="权限位"><legend>权限位（可多选）</legend>
        {(['view', 'download', 'edit', 'review', 'comment', ...(manage ? ['manage'] : [])] as AclPermission[]).map(permission =>
          <label key={permission} style={{ marginRight: 12 }}><input type="checkbox" checked={selectedPermissions.includes(permission)}
            onChange={event => setPermissions(event.target.checked ? [...selectedPermissions, permission] : selectedPermissions.filter(bit => bit !== permission))} />{permission}</label>)}
      </fieldset>
      <p aria-label="批量确认摘要">{principals.length} 个主体、{selectedPermissions.length} 个权限位、共 {total} 条授权。</p>
      <p>已存在的授权将被跳过，不视为失败。</p>
      {principals.length > 50 && <Alert type="warning" title="主体上限为 50，请移除多余主体后提交。" />}
      <p>review 仅加入空间审核人池，不自动授予 view、download、足够密级或自审资格。</p>
      <p>comment 仅允许在可查看的已发布版本上发表评论与回复，不授予 view、足够密级或审核资格，也不会自动补授给已有授权。</p>
      {manage && <p>选择 manage 将委派空间管理资格，请在提交前确认。</p>}
    </ResourceWriteDialog>}
    {dialog === 'grant' && kind === 'documents' && <ResourceWriteDialog<GrantResult> title="授予授权" scope={`${kind}:${resourceId}:grant`} onClose={() => setDialog(null)} onRefresh={refresh} disabled={!principal || query.loading || !!query.error}
      build={data => spaceWrites.grant(kind, resourceId, principal!, fieldValue(data, 'permission') as AclPermission)}
      onDone={result => { setReceipt(`${result.alreadyGranted ? '授权已存在' : '授权已授予'}；因密级当前不生效的主体数量：${result.clearanceBlockedPrincipalCount}。授权不修改主体密级。`); setDialog(null); refresh(); onChanged(); }}>
      <PrincipalPicker kind={kind} resourceId={resourceId} onChange={setPrincipal} />
      <label>权限位<select name="permission" aria-label="权限位"><option value="view">view</option><option value="download">download</option><option value="edit">edit</option><option value="comment">comment</option></select></label>
      <p>comment 仅允许在可查看的已发布版本上发表评论与回复，不授予 view、足够密级或审核资格，也不会自动补授给已有授权。</p>
    </ResourceWriteDialog>}
    {dialog && typeof dialog !== 'string' && (dialog.permission !== 'manage' || manage) && <ResourceWriteDialog title="撤销授权" scope={`${kind}:${resourceId}:revoke:${dialog.id}`} onClose={() => setDialog(null)} onRefresh={refresh}
      build={() => spaceWrites.revoke(kind, resourceId, { principalType: dialog.principalType, principalId: dialog.principalId, label: dialog.principalName }, dialog.permission)}
      onDone={() => { setBatchJson(''); setRevokeJson(''); setReceipt('撤销请求已确认；撤销不存在的授权同样成功，不代表该授权此前一定存在。'); setDialog(null); refresh(); onChanged(); }}>
      <p>确认撤销 {dialog.principalName} 的 {dialog.permission}？{dialog.permission === 'manage' && '撤销后相关主体可能失去空间管理资格。'}</p>
    </ResourceWriteDialog>}
  </section>;
}

