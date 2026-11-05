import { VersionPreview } from '../office/VersionPreview';
import { useEffect, useLayoutEffect, useRef, useState, useSyncExternalStore } from 'react';
import { Alert, Button, Form, Input, Modal, Pagination, Select, Space, Table, Tag } from 'antd';
import { Link, useNavigate, useParams } from 'react-router-dom';
import { useSession } from '../SessionProvider';
import { DocumentOperationContext, useDocumentOperations } from '../documents/OperationContext';
import { DocumentOperations, operationFailure } from '../documents/operations';
import { canDelete } from '../documents/capabilities';
import { MetadataPanel } from '../documents/metadata/MetadataPanel';
import { MetadataFilters } from '../documents/metadata/Filters';
import { metadataApi, type Filters } from '../documents/metadata/api';
import { deletionTransport, DocumentDeletion } from '../documents/deletion';
import { DeleteConfirmation, DeletionFeedback } from '../documents/DeleteControls';
import { consumeDocumentDeleted, rememberDocumentDeleted } from '../documents/notice';
import { useResource } from '../useResource';
import { ApiError, isAbort, messageOf, positiveId } from '../api';
import { pendingProgress, progressLabel, retryable } from '../logic';
import { Feedback, PageHeader } from '../components';
import { DownloadButton } from '../FileAccess';
import { perm, useAdminAccess } from '../admin/access';
import { useFavorites } from '../favorites/useFavorites';
import { FavoriteButton } from '../favorites/FavoriteButton';
import { reviewPaths, type ReviewStatus } from '../reviews/api';
import { VersionReviewPanel } from '../reviews/VersionReviewPanel';
import { useReviewScope } from '../reviews/lifecycle';
import { VersionComments } from '../comments/CommentPanel';
import type { Capabilities, DocumentPage, DocumentSummary, Progress, QueryPage, Space as KnowledgeSpace, UploadResult, UploadSpace, Version } from '../types';

export function useProgress(documentId: string, versionId: string, revision = 0) {
  const { session, state: identity } = useSession();
  const key = `${identity.epoch}/${documentId}/${versionId}/${revision}`;
  const [state, setState] = useState<{ key: string; data?: Progress; error?: string; loading: boolean; denied?: boolean }>({ key, loading: true });
  useEffect(() => {
    const controller = new AbortController(); let timer: ReturnType<typeof setTimeout> | undefined, failures = 0;
    const stop = () => { if (document.visibilityState === 'hidden') { controller.abort(); clearTimeout(timer); setState({ key, loading: false }); } };
    document.addEventListener('visibilitychange', stop);
    setState({ key, loading: true });
    const load = async () => {
      try {
        const data = await session.json<Progress>(`/api/documents/${positiveId(documentId)}/progress?versionId=${positiveId(versionId)}`, { signal: controller.signal });
        if (controller.signal.aborted) return;
        failures = 0; setState({ key, data, loading: false });
        if (pendingProgress(data)) timer = setTimeout(() => void load(), 3000);
      } catch (error) {
        if (controller.signal.aborted || isAbort(error)) return;
        const denied = error instanceof ApiError && [401, 403, 404].includes(error.status);
        setState({ key, error: messageOf(error), loading: false, denied });
        if (!denied && ++failures < 3) timer = setTimeout(() => void load(), 3000 * failures);
      }
    };
    if (identity.phase === 'ready' && document.visibilityState !== 'hidden') void load();
    return () => { controller.abort(); clearTimeout(timer); document.removeEventListener('visibilitychange', stop); };
  }, [key, documentId, versionId, session]);
  return state.key === key ? state : { key, loading: true, data: undefined, error: undefined, denied: false };
}

export function ProgressCard(props: { documentId: string; versionId: string; revision?: number; submitted?: boolean; onDenied?: () => void }) {
  return props.submitted ? <SubmittedVersion documentId={props.documentId} versionId={props.versionId} /> : <ReadableProgress {...props} />;
}
function SubmittedVersion({ documentId, versionId }: { documentId: string; versionId: string }) {
  const { session, state } = useSession();
  const query = useResource(signal => session.json<ReviewStatus>(reviewPaths.status(documentId, versionId), { signal }), `${state.epoch}:${documentId}/${versionId}`);
  return <section className="panel"><h2>上传已受理</h2>
    {query.error ? <Alert type="info" title="提交成功，当前无法查看后续处理状态。" /> : <Feedback {...query} />}
    {query.data && <p>版本 {versionId} · {query.data.state.indexStatus} · {query.data.state.reviewState}。上传受理不表示已发布。</p>}
    <a href="/reviews">前往本人版本核对</a></section>;
}
function ReadableProgress({ documentId, versionId, revision, onDenied }: { documentId: string; versionId: string; revision?: number; onDenied?: () => void }) {
  const state = useProgress(documentId, versionId, revision);
  const denied = useRef(onDenied); denied.current = onDenied;
  useEffect(() => { if (state.denied) denied.current?.(); }, [state.denied]);
  return <section className="panel"><h2>选中版本处理状态</h2>
    <Feedback {...state} />
    {state.data && <><Tag color={state.data.indexStatus === 'indexed' ? 'green' : 'blue'}>{progressLabel(state.data)}</Tag>
      <p className="muted">文件受理与解析发布分别完成；只有已发布版本进入检索。</p>
      {state.data.parseTruncated && <Alert type="warning" title="解析结果被截断，文档尾部可能未处理。" />}
      {(state.data.parseIssueSummary || state.data.failureSummary) && <p>{state.data.parseIssueSummary ?? state.data.failureSummary}</p>}
    </>}
  </section>;
}

export function UploadDialog({ open, documentId, onClose, onUploaded }: { open: boolean; documentId?: string; onClose: () => void; onUploaded: (result: UploadResult) => void }) {
  const { session } = useSession();
  const { operations, state: documentState } = useDocumentOperations();
  const [spaces, setSpaces] = useState<UploadSpace[]>([]), [cursor, setCursor] = useState<string | null>(null), [levels, setLevels] = useState<number[]>([]);
  const [busy, setBusy] = useState(false), [loading, setLoading] = useState(false), [error, setError] = useState('');
  const [file, setFile] = useState<File | null>(null), [form] = Form.useForm();
  const lock = useRef(false), active = useRef<AbortController | null>(null);
  useEffect(() => {
    if (!open) return;
    const controller = new AbortController(); active.current = controller;
    setError(''); setSpaces([]); setLevels([]); setCursor(null); setFile(null); form.resetFields();
    if (!documentId) {
      setLoading(true);
      void Promise.all([session.json<QueryPage<UploadSpace>>('/api/auth/upload-spaces', { signal: controller.signal }),
        session.json<number[]>('/api/auth/security-levels', { signal: controller.signal })]).then(([page, dictionary]) => {
          if (!controller.signal.aborted) { setSpaces(page.items); setCursor(page.nextCursor); setLevels(dictionary); }
        }).catch(failure => { if (!controller.signal.aborted && !isAbort(failure)) setError(messageOf(failure)); })
        .finally(() => { if (!controller.signal.aborted) setLoading(false); });
    }
    return () => { controller.abort(); active.current = null; };
  }, [open, documentId, session, form]);
  return <Modal title={documentId ? '上传新版本' : '上传文档'} open={open} onCancel={() => { if (!busy) onClose(); }} footer={null} destroyOnHidden>
    {error && <Alert type="warning" title={error} style={{ marginBottom: 16 }} />}
    <Form form={form} layout="vertical" onFinish={async (values: { spaceId: string; securityLevel: number; title?: string }) => {
      if (lock.current) return;
      if (!file || file.size === 0 || file.size > 50 * 1024 * 1024) { setError('请选择非空且不超过 50 MiB 的文件；实际限制由服务器判定。'); return; }
      if (!/\.(pdf|txt|md|doc|docx|xls|xlsx|ppt|pptx)$/i.test(file.name)) { setError('只支持 PDF、TXT、Markdown（.md）和常规 Office 文档。'); return; }
      const operation = operations?.beginWrite('upload-version');
      if (operations && !operation) return;
      const request = active.current;
      lock.current = true; setBusy(true); setError('');
      try {
        const body = new FormData(); body.set('file', file);
        if (!documentId) { body.set('spaceId', positiveId(values.spaceId)); body.set('securityLevel', String(values.securityLevel)); if (values.title) body.set('title', values.title); }
        const result = await session.json<UploadResult>(documentId ? `/api/documents/${positiveId(documentId)}/versions` : '/api/documents', { method: 'POST', body, signal: request?.signal });
        operation?.settle('confirmed');
        if (request?.signal.aborted) return;
        onUploaded(result); onClose();
      } catch (failure) {
        operation?.settle(operationFailure(failure));
        if (!request?.signal.aborted && !isAbort(failure)) setError(failure instanceof ApiError && failure.status === 0 ? '提交结果尚未确认，请先核对文档列表，不会自动重复上传。' : messageOf(failure));
      }
      finally { lock.current = false; setBusy(false); }
    }}>
      {!documentId && <><Form.Item name="spaceId" label="目标空间" rules={[{ required: true, message: '请选择可上传空间' }]}>
        <Select loading={loading} placeholder="选择有编辑资格的空间" options={spaces.map(space => ({ value: space.spaceId, label: space.name }))} /></Form.Item>
        {cursor && <Button size="small" disabled={loading} onClick={async () => {
          setLoading(true); setError('');
          try { const page = await session.json<QueryPage<UploadSpace>>(`/api/auth/upload-spaces?cursor=${encodeURIComponent(cursor)}`, { signal: active.current?.signal });
            if (!active.current?.signal.aborted) { setSpaces(previous => [...new Map([...previous, ...page.items].map(item => [item.spaceId, item])).values()]); setCursor(page.nextCursor); } }
          catch (failure) { if (!isAbort(failure)) setError(messageOf(failure)); } finally { setLoading(false); }
        }}>更多可上传空间</Button>}
        <Form.Item name="title" label="文档标题（可选）"><Input placeholder="缺省使用文件名" /></Form.Item>
        <Form.Item name="securityLevel" label="密级" rules={[{ required: true, message: '请选择密级' }]}><Select options={levels.map(value => ({ value, label: `密级 ${value}` }))} /></Form.Item>
      </>}
      <Form.Item label="原始文件" required><input aria-label="原始文件" type="file" accept=".pdf,.txt,.md,.doc,.docx,.xls,.xlsx,.ppt,.pptx" onChange={event => setFile(event.target.files?.[0] ?? null)} /></Form.Item>
      <p className="muted">支持 PDF、文本和常规 Office。上传成功后将异步解析。</p>
      <Button type="primary" htmlType="submit" loading={busy} disabled={documentState.exclusive || loading || (!documentId && (!spaces.length || !levels.length))}>提交文件</Button>
    </Form>
  </Modal>;
}

export function DocumentsPage() {
  const scope = useReviewScope();
  return scope.active ? <DocumentsContent key={scope.key} /> : <Alert type="info" title="请先完成登录与身份确认" />;
}
function DocumentsContent() {
  const { session, state: identity } = useSession();
  const [deletionNotice, setDeletionNotice] = useState<number | null>(null);
  useEffect(() => {
    if (consumeDocumentDeleted(session)) setDeletionNotice(identity.epoch);
    const timer = setTimeout(() => setDeletionNotice(null), 5000);
    return () => clearTimeout(timer);
  }, [session, identity.epoch]);
  const [page, setPage] = useState(1), [filters, setFilters] = useState<Filters>({});
  const [upload, setUpload] = useState(false), [submitted, setSubmitted] = useState<UploadResult | null>(null);
  const query = useResource(signal => metadataApi.query(session, filters, page, signal), `${page}/${JSON.stringify(filters)}`);
  const spaces = useResource(signal => session.json<KnowledgeSpace[]>('/api/admin/spaces', { signal }), 'spaces');
  const favorites = useFavorites(query.data?.items.map(item => item.id) ?? [], query.data);
  return <><PageHeader title="文档中心" description="整理团队知识，在权限范围内发现可靠资料" extra={<Button type="primary" size="large" onClick={() => setUpload(true)}>上传文档</Button>} />
    {deletionNotice === identity.epoch && <Alert type="success" title="文档已删除" />}
    {submitted && !favorites.unavailable(submitted.documentId) && <ProgressCard key={submitted.versionId} documentId={submitted.documentId} versionId={submitted.versionId} submitted />}
    <section className="panel"><MetadataFilters spaces={spaces.data ?? []} onSubmit={value => { setFilters(value); setPage(1); }} />
      {spaces.error && <Alert type="warning" title="空间目录暂不可用，仍可查看全部文档。" style={{ marginBottom: 16 }} />}
      <Feedback {...query} retry={query.reload} />{query.data && <><Table<DocumentSummary> rowKey="id" pagination={false} dataSource={query.data.items.filter(item => !favorites.unavailable(item.id))} columns={[
        { title: '文档', dataIndex: 'title', render: (title, row) => <Link to={`/documents/${row.id}`}>{title}</Link> },
        { title: '当前版本', render: (_, row) => row.currentVersionNo == null ? '尚未发布' : `v${row.currentVersionNo}` },
        { title: '密级', dataIndex: 'securityLevel', render: value => <Tag>{`密级 ${value}`}</Tag> },
        { title: '最近更新', dataIndex: 'updatedAt', render: value => value ? new Date(value).toLocaleDateString('zh-CN') : '—' },
        { title: '个人收藏', render: (_, row) => <FavoriteButton id={row.id} favorites={favorites} /> },
      ]} /><Pagination style={{ marginTop: 24 }} current={page} total={Number(query.data.total)} pageSize={20} showSizeChanger={false} onChange={setPage} /></>}
    </section><UploadDialog open={upload} onClose={() => setUpload(false)} onUploaded={result => { setSubmitted(result); query.reload(); }} />
  </>;
}

export function DocumentDetailPage() {
  const { documentId = '' } = useParams();
  const scope = useReviewScope();
  return scope.active ? <DocumentWorkspace key={`${scope.key}:${documentId}`} documentId={documentId} /> : <Alert type="info" title="请先完成登录与身份确认" />;
}
function DocumentWorkspace({ documentId }: { documentId: string }) {
  const { session } = useSession(), navigate = useNavigate();
  const [operations] = useState(() => new DocumentOperations());
  const [controller] = useState(() => new DocumentDeletion(documentId, operations, session, deletionTransport(session), () => {
    rememberDocumentDeleted(session); navigate('/documents', { replace: true });
  }, () => document.visibilityState !== 'hidden'));
  const deletion = useSyncExternalStore(controller.subscribe, controller.getSnapshot);
  const operationState = useSyncExternalStore(operations.subscribe, operations.getSnapshot);
  useLayoutEffect(() => {
    controller.activate();
    const epoch = session.getSnapshot().epoch;
    const hide = () => { if (document.visibilityState === 'hidden') controller.dispose(); };
    const changed = () => {
      if (session.getSnapshot().phase !== 'ready' || session.getSnapshot().epoch !== epoch) controller.dispose();
    };
    const unsubscribe = session.subscribe(changed);
    document.addEventListener('visibilitychange', hide);
    return () => { unsubscribe(); document.removeEventListener('visibilitychange', hide); controller.dispose(); };
  }, [controller, session]);
  return <DocumentOperationContext value={operations}>
    {deletion.showContent ? <DocumentDetail documentId={documentId} onDelete={controller.open} onInvalidate={controller.invalidateConfirmation} />
      : <PageHeader title="文档详情" description="删除结果与当前状态" extra={<Link to="/documents">返回文档中心</Link>} />}
    <DeletionFeedback state={deletion} onVerify={() => void controller.verify()} onReconfirm={() => void controller.open()} />
    <DeleteConfirmation state={deletion} operations={operationState} onCancel={controller.cancel} onConfirm={acknowledge => void controller.submit(acknowledge)} />
  </DocumentOperationContext>;
}
function DocumentDetail({ documentId, onDelete, onInvalidate }: { documentId: string; onDelete: () => Promise<void>; onInvalidate: (unavailable?: boolean) => void }) {
  const { session } = useSession();
  const { operations, state: documentState } = useDocumentOperations();
  const adminAccess = useAdminAccess();
  const query = useResource(signal => session.json<DocumentSummary>(`/api/documents/${positiveId(documentId)}`, { signal }), documentId);
  const capabilities = useResource(signal => session.json<Capabilities>(`/api/documents/${positiveId(documentId)}/capabilities`, { signal }), documentId);
  const favorites = useFavorites(query.data ? [documentId] : [], query.data);
  const blocked = favorites.unavailable(documentId);
  const detail = blocked ? undefined : query.data;
  const [selected, setSelected] = useState<string | null>(null), [revision, setRevision] = useState(0), [upload, setUpload] = useState(false), [preview, setPreview] = useState<string | null>(null);
  const [busy, setBusy] = useState(false), [error, setError] = useState('');
  const [metadataTitle, setMetadataTitle] = useState<string | null>(null);
  const [versionStatus, setVersionStatus] = useState<ReviewStatus | undefined>();
  const [deniedVersions, setDeniedVersions] = useState<Set<string>>(() => new Set());
  const lock = useRef(false);
  const retryRequest = useRef<AbortController | null>(null);
  useEffect(() => () => retryRequest.current?.abort(), []);
  useEffect(() => {
    if (blocked || query.status === 404 || capabilities.status === 404) onInvalidate(true);
    else if (query.error || capabilities.error || (capabilities.data && !canDelete(capabilities.data))) onInvalidate();
  }, [blocked, query.status, query.error, capabilities.status, capabilities.error, capabilities.data, onInvalidate]);
  useEffect(() => { setSelected(null); setPreview(null); setUpload(false); setError(''); }, [documentId]);
  useEffect(() => { if (query.error || blocked) { setPreview(null); setUpload(false); setSelected(null); } }, [query.error, blocked]);
  const versions = (detail?.versions ?? []).filter(item => !deniedVersions.has(item.id));
  const version = selected ? versions.find(item => item.id === selected) : versions.find(item => item.id === detail?.currentVersionId);
  const versionId = version?.id;
  const path = versionId ? `/api/documents/${documentId}/versions/${versionId}` : '';
  const currentStatus = versionStatus?.state.versionId === versionId ? versionStatus : undefined;
  const canRead = currentStatus?.capabilities.canRead === true;
  const loseVersion = () => {
    if (versionId) setDeniedVersions(previous => new Set([...previous, versionId]));
    setVersionStatus(undefined); setPreview(null); setSelected(null); query.reload(); capabilities.reload();
  };
  useEffect(() => { if (!canRead) setPreview(null); }, [versionId, canRead]);
  return <><PageHeader title={detail ? metadataTitle ?? detail.title : '文档详情'} description="版本、处理状态与原始文件" extra={<Link to="/documents">返回文档中心</Link>} />
    <Feedback {...query} retry={query.reload} />{blocked && <Alert type="info" title="文档当前不可查看" action={<Button onClick={query.reload}>重新读取</Button>} />}{detail && <>
      <section className="panel"><div className="toolbar"><Tag>密级 {detail.securityLevel}</Tag><span className="muted">当前发布：{detail.currentVersionNo == null ? '尚无版本' : `v${detail.currentVersionNo}`}</span></div>
        <Space wrap><FavoriteButton id={documentId} favorites={favorites} />{(adminAccess.canManageSpace(detail.spaceId) || adminAccess.has(perm.securityLevelWrite)) && <Link to={`/admin/documents/${documentId}`}>管理此文档</Link>}<Button disabled={documentState.exclusive || !capabilities.data?.canUploadVersion} onClick={() => setUpload(true)}>上传新版本</Button>
          <Button disabled={!versionId || !canRead} onClick={() => setPreview(`${path}/preview`)}>预览选中版本</Button>
          {versionId && <DownloadButton key={path} path={`${path}/download`} name={version?.fileName ?? detail.title} disabled={!capabilities.data?.canDownload || !canRead || !currentStatus?.capabilities.canDownload} onDenied={loseVersion} />}
          <Button loading={busy} disabled={documentState.exclusive || !capabilities.data?.canRetry || !version || !retryable(version)} onClick={async () => {
            if (lock.current || !versionId) return;
            const operation = operations?.beginWrite('parse-retry');
            if (operations && !operation) return;
            const controller = new AbortController(); retryRequest.current = controller;
            lock.current = true; setBusy(true); setError('');
            try {
              await session.json(`/api/documents/${documentId}/retry?versionId=${versionId}`, { method: 'POST', signal: controller.signal });
              operation?.settle('confirmed');
              if (!controller.signal.aborted) { setRevision(value => value + 1); query.reload(); }
            } catch (failure) {
              operation?.settle(operationFailure(failure));
              if (!controller.signal.aborted && !isAbort(failure)) { setError(messageOf(failure)); capabilities.reload(); }
            } finally { lock.current = false; setBusy(false); }
          }}>重试失败解析</Button>
          <Button danger disabled={documentState.exclusive || !canDelete(capabilities.data)} onClick={() => void onDelete()}>删除文档</Button></Space>
        {capabilities.error && <Alert style={{ marginTop: 16 }} type="warning" title="操作资格暂不可确认，相关操作已禁用。" />}
        {error && <Alert style={{ marginTop: 16 }} type="error" title={error} />}
      </section>
      <MetadataPanel documentId={documentId} canEdit={capabilities.data?.canEditMetadata === true} onTitle={setMetadataTitle}
        onDenied={status => { capabilities.reload(); if (status === 404) query.reload(); }} />
      {versionId && <><VersionReviewPanel key={`review:${documentId}/${versionId}/${revision}`} documentId={documentId} versionId={versionId} files={false} onStatus={setVersionStatus} onDenied={loseVersion}
        onChanged={() => { setPreview(null); query.reload(); capabilities.reload(); setRevision(value => value + 1); }} />
        {canRead && <ProgressCard key={`progress:${documentId}/${versionId}/${revision}`} documentId={documentId} versionId={versionId} revision={revision} onDenied={loseVersion} />}
        <VersionComments key={`${documentId}/${versionId}`} documentId={documentId} versionId={versionId} status={currentStatus} /></>}
      {!versionId && <p>默认读取当前发布版本；查看候选时请从可见版本列表明确选择。</p>}
      <section className="panel"><h2>版本历史</h2><Table<Version> rowKey="id" pagination={false} dataSource={versions} columns={[
        { title: '版本', render: (_, value) => <Button type="link" onClick={() => setSelected(value.id)}>v{value.versionNo}{value.current ? ' · 当前发布' : ''}</Button> },
        { title: '文件名', dataIndex: 'fileName' }, { title: '处理状态', render: (_, value) => progressLabel(value) },
      ]} /></section>
    </>}
    <UploadDialog open={!!detail && upload} documentId={documentId} onClose={() => setUpload(false)} onUploaded={result => { setSelected(result.versionId); setRevision(value => value + 1); query.reload(); }} />
    <VersionPreview path={detail && canRead ? preview : null} fileName={version?.fileName} onClose={() => setPreview(null)} onDenied={loseVersion} />
  </>;
}
