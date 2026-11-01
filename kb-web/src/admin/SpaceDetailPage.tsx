import { useState } from 'react';
import { Alert, Button, Space, Tag } from 'antd';
import { Link, useNavigate, useParams } from 'react-router-dom';
import { PageHeader } from '../components';
import { useSession } from '../SessionProvider';
import type { QueryPage } from '../types';
import { useAdminAccess } from './access';
import { AclPanel } from './AclPanel';
import { useAdminResource, useCursorList } from './hooks';
import { spacePaths, spaceWrites, type AdminSpace, type ManagedDocument } from './spaceApi';
import { fieldValue, ResourceStatus, ResourceWriteDialog, TextField, validTarget } from './spaceUi';
import { PublicationPolicyPanel } from '../reviews/PolicyPanel';
import { CategoryManager } from './DocumentTaxonomy';
export function SpaceDetailPage() {
  const { spaceId = '' } = useParams(), { state } = useSession();
  return validTarget(spaceId) ? <SpaceDetail key={`${state.epoch}:${spaceId}`} id={spaceId} /> : <Alert type="info" title="目标不可访问" />;
}
function SpaceDetail({ id }: { id: string }) {
  const { session } = useSession(), access = useAdminAccess();
  const query = useAdminResource<AdminSpace>(signal => session.json(spacePaths.space(id), { signal }), `space:${id}`);
  const [dialog, setDialog] = useState<'edit' | 'archive' | 'delete' | null>(null);
  const refresh = () => query.reload();
  const changed = () => { query.reload(); access.check(true); };
  const allowed = !query.loading && !query.error && query.data?.manageable;
  return <><PageHeader title={query.data?.name ?? `空间 ${id}`} extra={<Link to="/admin/spaces">返回空间目录</Link>} />
    <ResourceStatus {...query} retry={query.reload} />
    {!query.loading && !query.error && !allowed && <Alert type="info" title="无管理资格或目标不可访问" />}
    {allowed && query.data && <><section className="panel"><p>编码：{query.data.code}（创建后只读）</p><p>{query.data.description}</p><Tag>{query.data.archived ? '已归档' : '未归档'}</Tag>
      <Space><Button onClick={() => setDialog('edit')}>修改空间</Button><Button onClick={() => setDialog('archive')}>{query.data.archived ? '取消归档' : '归档空间'}</Button><Button danger onClick={() => setDialog('delete')}>删除空间</Button></Space></section>
      <PublicationPolicyPanel spaceId={id} onChanged={changed} />
      <CategoryManager spaceId={id} archived={query.data.archived} />
      <AclPanel key={id} kind="spaces" resourceId={id} onChanged={changed} />
      <ManagedDocuments id={id} />
      {dialog === 'edit' && <ResourceWriteDialog title="修改空间" scope={`space:${id}:metadata`} onClose={() => setDialog(null)} onRefresh={refresh} onDone={() => { setDialog(null); changed(); }}
        build={data => spaceWrites.update(id, fieldValue(data, 'name'), fieldValue(data, 'description'), query.data!.version)}>
        <p>当前版本：{String(query.data.version)}。版本变化后需要重新确认。</p><TextField key={`name:${query.data.version}`} name="name" label="空间名称" value={query.data.name} max={200} />
        <TextField key={`desc:${query.data.version}`} name="description" label="空间描述" value={query.data.description ?? ''} max={2000} required={false} multiline />
      </ResourceWriteDialog>}
      {dialog === 'archive' && <ResourceWriteDialog title={query.data.archived ? '取消归档' : '归档空间'} scope={`space:${id}:archive`} onClose={() => setDialog(null)} onRefresh={refresh} onDone={() => { setDialog(null); changed(); }}
        build={() => spaceWrites.archive(id, !query.data!.archived, query.data!.version)}>
        <p>{query.data.archived ? '取消归档后按原有授权与密级恢复文档可见性。' : '归档后文档退出检索、列表、详情、下载与预览；管理资格保留，可以取消归档。'}</p>
      </ResourceWriteDialog>}
      {dialog === 'delete' && <DeleteSpace id={id} onClose={() => setDialog(null)} refresh={refresh} />}
    </>}
  </>;
}
export function ManagedDocuments({ id }: { id: string }) {
  const { session } = useSession();
  const list = useCursorList<ManagedDocument>((cursor, signal) => session.json<QueryPage<ManagedDocument>>(spacePaths.documents({ spaceId: id, cursor }), { signal }), `managed-documents:${id}`);
  return <section className="panel"><h2>空间内文档</h2><ResourceStatus {...list} retry={list.reload} />
    <ul className="admin-list">{list.items.map(item => <li key={item.documentId}><Link to={`/admin/documents/${item.documentId}`}>文档 {item.documentId}</Link><span>{item.spaceArchived ? '空间已归档' : '空间未归档'}</span></li>)}</ul>
    {!list.loading && !list.error && !list.items.length && <p>暂无文档</p>}{list.hasMore && <Button disabled={list.loading} onClick={list.more}>继续读取文档</Button>}
    <p className="muted">管理清单不提供阅读、预览或下载资格。</p></section>;
}
function DeleteSpace({ id, onClose, refresh }: { id: string; onClose: () => void; refresh: () => void }) {
  const { session } = useSession(), access = useAdminAccess(), navigate = useNavigate();
  const probe = useAdminResource<QueryPage<ManagedDocument>>(signal => session.json(spacePaths.documents({ spaceId: id, limit: 1 }), { signal }), `delete:${id}`);
  const leave = () => { access.check(true); navigate('/admin/spaces', { replace: true }); };
  const verify = async () => {
    try {
      const current = await session.json<AdminSpace>(spacePaths.space(id));
      if (!current.manageable) { refresh(); access.check(true); onClose(); } else probe.reload();
    } catch (error) {
      if (error && typeof error === 'object' && 'status' in error && error.status === 404) leave();
      else { refresh(); access.check(true); probe.reload(); }
    }
  };
  return <ResourceWriteDialog title="删除空间" scope={`space:${id}:delete`} onClose={onClose} onRefresh={() => { probe.reload(); void verify(); }} disabled={probe.loading || !!probe.error || !probe.data || probe.data.items.length > 0}
    build={() => spaceWrites.delete(id)} onDone={leave}>
    <ResourceStatus {...probe} retry={probe.reload} />
    {probe.data?.items.length ? <Alert type="info" title="空间仍有文档，请改用归档。" /> : null}
    <p>删除不可恢复，空间授权将一并移除。此检查仅减少必然失败的提交，以服务端最终判定为准。</p>
  </ResourceWriteDialog>;
}
