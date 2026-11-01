import { useEffect, useRef, useState } from 'react';
import { Alert, Button } from 'antd';
import { useSession } from '../../SessionProvider';
import { useResource } from '../../useResource';
import { ResourceStatus } from '../../admin/spaceUi';
import { useAdminWrite, type AdminWrite, type WriteOutcome } from '../../admin/hooks';
import { AdminModal, WriteNotice } from '../../admin/ui';
import { useDocumentOperations } from '../OperationContext';
import { CategoryBrowser, TagPicker } from './Selectors';
import { metadataApi, metadataWrite, metadataWrites, type Metadata, type Receipt, type Tag } from './api';

export function MetadataPanel({ documentId, canEdit, onTitle, onDenied }: { documentId: string; canEdit: boolean; onTitle: (title: string) => void; onDenied?: (status: number) => void }) {
  const { session } = useSession(), { state: operations } = useDocumentOperations();
  const query = useResource(signal => metadataApi.get(session, documentId, signal), documentId);
  const [open, setOpen] = useState(false), [saved, setSaved] = useState(false);
  const denied = useRef(onDenied); denied.current = onDenied;
  useEffect(() => { if (query.status === 403 || query.status === 404) denied.current?.(query.status); }, [query.status]);
  useEffect(() => { if (query.data) onTitle(query.data.title); }, [query.data, onTitle]);
  return <section className="panel"><h2>文档资料</h2><p className="muted">整文档资料即时生效，不随所选文件版本切换，也不进入文件审核。</p>
    {saved && <Alert type="success" title="资料已保存；下方读取显示当前资料。" />}
    <ResourceStatus loading={query.loading} error={query.error} retry={query.reload} />
    {query.data && <><p style={{ whiteSpace: 'pre-wrap' }}>{query.data.summary ?? '暂无人工摘要'}</p><p>分类：{query.data.category?.path.map(node => node.name).join(' / ') ?? '未分类'}</p>
      <p>标签：{query.data.tags.map(tag => `${tag.name}${tag.enabled ? '' : '（停用）'}`).join('、') || '无'}</p></>}
    <Button disabled={!canEdit || operations.exclusive || query.loading || !query.data} onClick={() => setOpen(true)}>编辑资料</Button>
    {open && <MetadataEditor documentId={documentId} canEdit={canEdit} onDenied={onDenied} onClose={() => setOpen(false)} onSaved={() => { setOpen(false); setSaved(true); query.reload(); }} />}
  </section>;
}
export function MetadataEditor({ documentId, canEdit, onClose, onSaved, onDenied }: { documentId: string; canEdit: boolean; onClose: () => void; onSaved: () => void; onDenied?: (status: number) => void }) {
  const { session } = useSession(), write = useAdminWrite(`metadata:${documentId}`, metadataWrite);
  const query = useResource(signal => metadataApi.get(session, documentId, signal), documentId);
  const denied = useRef(onDenied); denied.current = onDenied;
  useEffect(() => { if (query.status === 403 || query.status === 404) denied.current?.(query.status); }, [query.status]);
  const allowed = canEdit && ![403, 404].includes(write.status ?? 0) && ![403, 404].includes(query.status ?? 0);
  const receive = (result: WriteOutcome<Receipt>) => {
    if (result.status === 'confirmed') onSaved();
    else if (result.status === 'rejected' && [403, 404].includes(result.error.status)) onDenied?.(result.error.status);
  };
  return <AdminModal title="编辑整文档资料" write={write} onClose={onClose}>
    <WriteNotice write={write} disabled={!allowed || write.blocked} onRetry={() => void write.retry<Receipt>().then(receive)} onVerify={query.reload} />
    <ResourceStatus loading={query.loading} error={query.error} retry={query.reload} /><Button disabled={write.busy} onClick={query.reload}>重新读取资料</Button>
    {query.data && <MetadataForm key={`${documentId}:${query.revision}`} data={query.data} write={write} canEdit={allowed} receive={receive} />}
  </AdminModal>;
}
function MetadataForm({ data, write, canEdit, receive }: { data: Metadata; write: AdminWrite; canEdit: boolean; receive: (result: WriteOutcome<Receipt>) => void }) {
  const [title, setTitle] = useState(data.title), [summary, setSummary] = useState(data.summary ?? '');
  const [category, setCategory] = useState(data.category), [tags, setTags] = useState<Tag[]>(data.tags), [ready, setReady] = useState(false);
  return <form onSubmit={event => { event.preventDefault(); if (canEdit && ready && !write.blocked) void write.submit<Receipt>(() => metadataWrites.update(data.documentId,
    { title, summary, categoryId: category?.id ?? null, tagIds: tags.map(tag => tag.id), expectedMetadataVersion: data.metadataVersion })).then(receive); }}>
    <fieldset disabled={write.busy || write.blocked || !canEdit}><p>资料版本 {String(data.metadataVersion)} · 保存后立即生效</p>
      <label>标题<input aria-label="资料标题" value={title} onChange={event => setTitle(event.target.value)} required /></label>
      <label>人工摘要<textarea aria-label="人工摘要" value={summary} onChange={event => setSummary(event.target.value)} /></label>
      <p>已选分类：{category?.path.map(node => node.name).join(' / ') ?? '未分类'}</p>
      <CategoryBrowser context={{ kind: 'edit', id: data.documentId }} selected={category?.id ?? null} onSelect={setCategory} onReady={page => setReady(page !== null)} />
      <TagPicker kind="edit" documentId={data.documentId} value={tags} onChange={setTags} />
      <Button htmlType="submit" type="primary" disabled={!ready || write.pending || !canEdit || write.blocked}>保存资料</Button>
    </fieldset>
  </form>;
}
