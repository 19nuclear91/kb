import { useEffect, useState } from 'react';
import { Alert, Button } from 'antd';
import { useSession } from '../SessionProvider';
import { perm, useAdminAccess } from './access';
import { useAdminResource } from './hooks';
import { PrincipalPicker } from './PrincipalPicker';
import { spacePaths, spaceWrites, type Acl, type AclPermission, type GrantResult, type Principal, type ResourceKind } from './spaceApi';
import { fieldValue, ResourceStatus, ResourceWriteDialog, useResourceReceipt } from './spaceUi';

export function AclPanel({ kind, resourceId, archived = false, onChanged, onData }: {
  kind: ResourceKind; resourceId: string; archived?: boolean; onChanged: () => void; onData?: (items: Acl[] | undefined) => void;
}) {
  const { session } = useSession(), access = useAdminAccess();
  const query = useAdminResource<Acl[]>(signal => session.json(spacePaths.acl(kind, resourceId), { signal }), `${kind}:${resourceId}`);
  const [dialog, setDialog] = useState<'grant' | Acl | null>(null), [principal, setPrincipal] = useState<Principal | null>(null);
  const [receipt, setReceipt] = useResourceReceipt(`acl:${kind}:${resourceId}`);
  const manage = kind === 'spaces' && access.has(perm.grantManage);
  const refresh = () => query.reload();
  const confirmed = query.loading || query.error ? undefined : query.data;
  useEffect(() => { onData?.(confirmed); }, [confirmed, onData]);
  // 父面板只接收当前已经确认的例外数量，重读或失败时立即作废旧数量。
  return <section className="panel"><h2>{kind === 'spaces' ? '空间授权' : '文档例外授权'}</h2>
    {kind === 'documents' && <p>例外授权只扩权，不收紧空间授权。{archived && '所属空间已归档，取消归档前例外不会使文档可见。'}</p>}
    <ResourceStatus {...query} retry={query.reload} />{receipt && <Alert type="success" title={receipt} />}
    {!query.loading && !query.error && query.data && <><Button onClick={() => { setPrincipal(null); setDialog('grant'); }}>授予授权</Button>
      <ul className="admin-list">{query.data.map(acl => <li key={acl.id}><span>{acl.principalType} · {acl.principalName} · {acl.permission}</span>
        {(acl.permission !== 'manage' || manage) && <Button aria-label={`撤销 ${acl.principalName} ${acl.permission}`} onClick={() => setDialog(acl)}>撤销</Button>}</li>)}</ul></>}
    {dialog === 'grant' && <ResourceWriteDialog<GrantResult> title="授予授权" scope={`${kind}:${resourceId}:grant`} onClose={() => setDialog(null)} onRefresh={refresh} disabled={!principal || query.loading || !!query.error}
      build={data => spaceWrites.grant(kind, resourceId, principal!, fieldValue(data, 'permission') as AclPermission)}
      onDone={result => { setReceipt(`${result.alreadyGranted ? '授权已存在' : '授权已授予'}；因密级当前不生效的主体数量：${result.clearanceBlockedPrincipalCount}。授权不修改主体密级。`); setDialog(null); refresh(); onChanged(); }}>
      <PrincipalPicker kind={kind} resourceId={resourceId} onChange={setPrincipal} />
      <label>权限位<select name="permission" aria-label="权限位"><option value="view">view</option><option value="download">download</option><option value="edit">edit</option>{kind === 'spaces' && <option value="review">review</option>}<option value="comment">comment</option>{manage && <option value="manage">manage</option>}</select></label>
      {kind === 'spaces' && <p>review 仅加入空间审核人池，不自动授予 view、download、足够密级或自审资格。</p>}
      <p>comment 仅允许在可查看的已发布版本上发表评论与回复，不授予 view、足够密级或审核资格，也不会自动补授给已有授权。</p>
      {manage && <p>选择 manage 将委派空间管理资格，请在提交前确认。</p>}
    </ResourceWriteDialog>}
    {dialog && dialog !== 'grant' && (dialog.permission !== 'manage' || manage) && <ResourceWriteDialog title="撤销授权" scope={`${kind}:${resourceId}:revoke:${dialog.id}`} onClose={() => setDialog(null)} onRefresh={refresh}
      build={() => spaceWrites.revoke(kind, resourceId, { principalType: dialog.principalType, principalId: dialog.principalId, label: dialog.principalName }, dialog.permission)}
      onDone={() => { setReceipt('撤销请求已确认；撤销不存在的授权同样成功，不代表该授权此前一定存在。'); setDialog(null); refresh(); onChanged(); }}>
      <p>确认撤销 {dialog.principalName} 的 {dialog.permission}？{dialog.permission === 'manage' && '撤销后相关主体可能失去空间管理资格。'}</p>
    </ResourceWriteDialog>}
  </section>;
}

