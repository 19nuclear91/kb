import { useState } from 'react';
import { Alert, Button } from 'antd';
import { Link, useParams } from 'react-router-dom';
import { PageHeader } from '../components';
import { useSession } from '../SessionProvider';
import type { DocumentSummary, QueryPage } from '../types';
import { adminPaths } from './api';
import { perm, useAdminAccess } from './access';
import { AclPanel } from './AclPanel';
import { useAdminResource } from './hooks';
import { spacePaths, spaceWrites, type Acl, type ManagedDocument, type RelocationResult, type SecurityResult, type SecurityState } from './spaceApi';
import { fieldValue, ResourceStatus, ResourceWriteDialog, TextField, validTarget, useResourceReceipt } from './spaceUi';
export function DocumentPanelPage() {
  const { documentId = '' } = useParams(), { state } = useSession();
  return validTarget(documentId) ? <DocumentPanel key={`${state.epoch}:${documentId}`} id={documentId} /> : <Alert type="info" title="目标不可访问" />;
}
function DocumentPanel({ id }: { id: string }) {
  const { session } = useSession(), access = useAdminAccess(), [acl, setAcl] = useState<Acl[] | undefined>(), [revision, setRevision] = useState(0);
  const [receipt, setReceipt] = useResourceReceipt(`document:${id}`);
  const membership = useAdminResource<QueryPage<ManagedDocument>>(signal => session.json(spacePaths.documents({ documentId: id }), { signal }), `document-owner:${id}`);
  const title = useAdminResource<DocumentSummary>(signal => session.json(`/api/documents/${id}`, { signal }), `document-title:${id}`);
  const owner = !membership.loading && !membership.error ? membership.data?.items[0] : undefined;
  const securityAllowed = access.has(perm.securityLevelWrite);
  const refresh = () => { membership.reload(); title.reload(); setAcl(undefined); setRevision(value => value + 1); };
  const changed = () => { refresh(); access.check(true); };
  return <><PageHeader title={title.data?.title ?? `文档 ${id}`} description="各管理区块独立按资格开放" extra={<Link to="/admin/spaces">返回空间目录</Link>} />
    {receipt && <Alert type="success" title={receipt} />}
    <ResourceStatus {...membership} retry={membership.reload} />
    {owner && <><p>当前空间 {owner.spaceId} · {owner.spaceArchived ? '已归档' : '未归档'}</p>
      <AclPanel key={`acl:${id}:${owner.spaceId}`} kind="documents" resourceId={id} archived={owner.spaceArchived} onChanged={() => {}} onData={setAcl} />
      <RelocationBox key={`relocate:${id}:${owner.spaceId}`} id={id} source={owner.spaceId} acl={acl} refresh={refresh} done={result => {
        setReceipt(`搬迁已完成；清除例外 ${result.clearedExceptionCount} 条${result.clearedExceptionsTruncated ? '（明细未完整列出）' : ''}；目标空间因密级受阻主体：${result.clearanceBlockedPrincipalCount}。受阻不代表失败。`);
        changed();
      }} />
    </>}
    {securityAllowed && <SecurityBox key={`${id}:${revision}`} id={id} />}
    {!membership.loading && !membership.error && !owner && !securityAllowed && <Alert type="info" title="无可用管理操作" />}
  </>;
}
export function RelocationBox({ id, source, acl, refresh, done }: { id: string; source: string; acl: Acl[] | undefined; refresh: () => void; done: (result: RelocationResult) => void }) {
  const access = useAdminAccess(), [open, setOpen] = useState(false), [target, setTarget] = useState('');
  const candidates = access.spaceStatus === 'ready' ? access.spaces.filter(space => !space.archived && space.spaceId !== source) : [];
  const available = candidates.some(space => space.spaceId === target);
  const reload = () => { refresh(); access.check(true); };
  return <section className="panel"><h2>文档搬迁</h2>
    {access.spaceStatus === 'loading' && <p role="status">正在确认搬迁目标…</p>}
    {access.spaceStatus === 'unavailable' && <Alert type="warning" title="搬迁目标暂不可读取。" action={<Button onClick={() => access.check(true)}>重新读取目标</Button>} />}
    {access.spaceStatus === 'ready' && !candidates.length && <p>没有可迁入的空间</p>}
    <Button disabled={!candidates.length || acl === undefined} onClick={() => { setTarget(''); setOpen(true); }}>搬迁文档</Button>
    {acl === undefined && <p>请等待例外授权列表就绪，确认将清除的条数。</p>}
    {open && <ResourceWriteDialog<RelocationResult> title="搬迁文档" scope={`document:${id}:relocate:${source}`} onClose={() => setOpen(false)} onRefresh={reload}
      disabled={!available || acl === undefined || access.spaceStatus !== 'ready'} build={() => spaceWrites.relocate(id, source, target)} onDone={result => { setOpen(false); done(result); }}>
      <label>目标空间<select aria-label="目标空间" value={available ? target : ''} onChange={event => setTarget(event.target.value)}><option value="">请选择目标空间</option>{candidates.map(space => <option key={space.spaceId} value={space.spaceId}>{space.name}</option>)}</select></label>
      <p>来源空间 {source}；将清除 {acl === undefined ? '尚未确认数量的' : acl.length} 条文档例外授权且不可恢复，需由目标空间管理员重新授出。搬迁不改变密级。</p>
    </ResourceWriteDialog>}
  </section>;
}
export function SecurityBox({ id }: { id: string }) {
  const { session, state } = useSession(), access = useAdminAccess(), [open, setOpen] = useState(false), [receipt, setReceipt] = useState('');
  const enabled = access.has(perm.securityLevelWrite);
  const security = useAdminResource<SecurityState>(enabled ? signal => session.json(spacePaths.security(id), { signal }) : null, `document-security:${id}`);
  const levels = useAdminResource<number[]>(enabled ? signal => session.json(adminPaths.securityLevels, { signal }) : null, `levels:${id}`);
  const ceiling = state.me?.user.clearanceLevel;
  const options = security.data && ceiling !== undefined && !levels.loading && !levels.error
    ? (levels.data ?? []).filter(value => value <= ceiling && value !== security.data!.securityLevel) : [];
  const refresh = () => { security.reload(); levels.reload(); };
  const ready = !security.loading && !security.error && !levels.loading && !levels.error && !!security.data;
  if (!enabled) return null;
  return <section className="panel"><h2>文档密级</h2>
    <ResourceStatus {...security} error={security.status === 403 ? '当前无法调整该文档密级。' : security.error} retry={security.reload} /><ResourceStatus {...levels} retry={levels.reload} />
    {receipt && <Alert type="success" title={receipt} />}{ready && <p>当前密级 {security.data!.securityLevel} · 密级版本 {String(security.data!.securityVersion)}</p>}
    {!levels.loading && !levels.error && !levels.data?.length && <p>密级字典为空，暂不可调整。</p>}
    <Button disabled={!ready || !options.length} onClick={() => setOpen(true)}>调整密级</Button>
    {open && <ResourceWriteDialog<SecurityResult> title="调整密级" scope={`document:${id}:security`} onClose={() => setOpen(false)} onRefresh={refresh} disabled={!ready || !options.length}
      build={data => { const selected = Number(fieldValue(data, 'securityLevel')); if (!options.includes(selected)) throw new Error('无效密级'); return spaceWrites.security(id, selected, security.data!.securityVersion, fieldValue(data, 'reason')); }}
      onDone={result => { setReceipt(`密级已调整：${result.previousSecurityLevel} → ${result.securityLevel}`); setOpen(false); refresh(); }}>
      <label>目标密级<select name="securityLevel" aria-label="目标密级" required defaultValue=""><option value="" disabled>请选择目标密级</option>{options.map(value => <option key={value} value={value}>{value}</option>)}</select></label>
      <TextField name="reason" label="调整原因" max={1000} multiline /><p>原因去首尾空白后最多 500 个 Unicode 字符。调高密级可能使当前读者失去访问；请确认后提交。</p>
    </ResourceWriteDialog>}
  </section>;
}
