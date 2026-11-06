import { VersionPreview } from '../office/VersionPreview';
import { useCallback, useEffect, useLayoutEffect, useRef, useState, useSyncExternalStore } from 'react';
import { Alert, Button, Space, Table, Tag } from 'antd';
import { Link, useNavigate, useParams } from 'react-router-dom';
import { useSession } from '../SessionProvider';
import { DocumentOperationContext, useDocumentOperations } from '../documents/OperationContext';
import { DocumentOperations, operationFailure } from '../documents/operations';
import { canDelete } from '../documents/capabilities';
import { MetadataPanel } from '../documents/metadata/MetadataPanel';
import { deletionTransport, DocumentDeletion } from '../documents/deletion';
import { DeleteConfirmation, DeletionFeedback } from '../documents/DeleteControls';
import { rememberDocumentDeleted } from '../documents/notice';
import { useResource } from '../useResource';
import { isAbort, messageOf, positiveId } from '../api';
import { progressLabel, retryable } from '../logic';
import { Feedback, PageHeader } from '../components';
import { DownloadButton } from '../FileAccess';
import { perm, useAdminAccess } from '../admin/access';
import { useFavorites } from '../favorites/useFavorites';
import { FavoriteButton } from '../favorites/FavoriteButton';
import type { ReviewStatus } from '../reviews/api';
import { VersionReviewPanel } from '../reviews/VersionReviewPanel';
import { useReviewScope } from '../reviews/lifecycle';
import { VersionComments } from '../comments/CommentPanel';
import { ProgressCard } from '../documents/ProgressCard';
import { UploadDialog } from '../documents/UploadDialog';
import type { Capabilities, DocumentSummary, Version } from '../types';

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
  const [factsRevision, setFactsRevision] = useState(0);
  const factsKey = `${documentId}/${factsRevision}`;
  const query = useResource(signal => session.json<DocumentSummary>(`/api/documents/${positiveId(documentId)}`, { signal }), factsKey);
  const capabilities = useResource(signal => session.json<Capabilities>(`/api/documents/${positiveId(documentId)}/capabilities`, { signal }), factsKey);
  const favorites = useFavorites(query.data ? [documentId] : [], query.data);
  const blocked = favorites.unavailable(documentId);
  const detail = blocked ? undefined : query.data;
  const [selected, setSelected] = useState<string | null>(null), [revision, setRevision] = useState(0), [upload, setUpload] = useState(false), [preview, setPreview] = useState<string | null>(null);
  const [busy, setBusy] = useState(false), [error, setError] = useState('');
  const [metadataTitle, setMetadataTitle] = useState<string | null>(null);
  const [versionStatus, setVersionStatus] = useState<{ key: string; value: ReviewStatus }>();
  const [deniedVersions, setDeniedVersions] = useState<Set<string>>(() => new Set());
  const lock = useRef(false);
  const retryRequest = useRef<AbortController | null>(null);
  useEffect(() => () => retryRequest.current?.abort(), []);
  useEffect(() => {
    if (blocked || [401, 403, 404].includes(query.status ?? 0) || [401, 403, 404].includes(capabilities.status ?? 0)) onInvalidate(true);
    else if (query.error || capabilities.error || (capabilities.data && !canDelete(capabilities.data))) onInvalidate();
  }, [blocked, query.status, query.error, capabilities.status, capabilities.error, capabilities.data, onInvalidate]);
  useEffect(() => { setSelected(null); setPreview(null); setUpload(false); setError(''); }, [documentId]);
  useEffect(() => {
    if (query.error || blocked) setPreview(null);
    if (blocked || [401, 403, 404].includes(query.status ?? 0) || [401, 403, 404].includes(capabilities.status ?? 0)) {
      setUpload(false); setSelected(null);
    }
  }, [query.error, query.status, capabilities.status, blocked]);
  const versions = (detail?.versions ?? []).filter(item => !deniedVersions.has(item.id));
  const version = selected ? versions.find(item => item.id === selected) : versions.find(item => item.id === detail?.currentVersionId);
  const versionId = version?.id;
  const path = versionId ? `/api/documents/${documentId}/versions/${versionId}` : '';
  const versionKey = `${query.key}/${query.revision}/${versionId ?? ''}/${revision}`;
  const liveVersion = useRef(versionKey); liveVersion.current = versionKey;
  const receiveStatus = useCallback((value: ReviewStatus | undefined) => {
    if (liveVersion.current !== versionKey) return;
    setVersionStatus(value ? { key: versionKey, value } : undefined);
  }, [versionKey]);
  const currentStatus = versionStatus?.key === versionKey && versionStatus.value.state.documentId === documentId
    && versionStatus.value.state.versionId === versionId ? versionStatus.value : undefined;
  const canRead = currentStatus?.capabilities.canRead === true;
  const factsLoading = query.loading || capabilities.loading;
  const canUploadVersion = !!detail && capabilities.data?.canUploadVersion === true && !factsLoading;
  const refreshFacts = useCallback(() => {
    liveVersion.current = '';
    setVersionStatus(undefined); setPreview(null); setMetadataTitle(null);
    setFactsRevision(value => value + 1);
    onInvalidate();
  }, [onInvalidate]);
  const recheck = () => {
    if (factsLoading || documentState.exclusive) return;
    setDeniedVersions(new Set()); refreshFacts();
  };
  const downloadDenied = () => {
    if (liveVersion.current !== versionKey || !versionId) return;
    setSelected(versionId);
    refreshFacts();
  };
  const loseVersion = () => {
    if (liveVersion.current !== versionKey) return;
    liveVersion.current = '';
    if (versionId) setDeniedVersions(previous => new Set([...previous, versionId]));
    setVersionStatus(undefined); setPreview(null); setSelected(versionId ?? selected); query.reload(); capabilities.reload();
  };
  useEffect(() => { if (!canRead) setPreview(null); }, [versionId, canRead]);
  return <><PageHeader title={detail ? metadataTitle ?? detail.title : '文档详情'} description="版本、处理状态与原始文件" extra={<Link to="/documents">返回文档中心</Link>} />
    <Button onClick={recheck} disabled={factsLoading || documentState.exclusive}>重新核对操作资格</Button>
    {factsLoading && <p role="status">正在核对文档与操作资格…</p>}
    <Feedback {...query} retry={recheck} />{blocked && <Alert type="info" title="文档当前不可查看" action={<Button onClick={recheck}>重新读取</Button>} />}{detail && <>
      <section className="panel"><div className="toolbar"><Tag>密级 {detail.securityLevel}</Tag><span className="muted">当前发布：{detail.currentVersionNo == null ? '尚无版本' : `v${detail.currentVersionNo}`}</span></div>
        <Space wrap><FavoriteButton id={documentId} favorites={favorites} />{(adminAccess.canManageSpace(detail.spaceId) || adminAccess.has(perm.securityLevelWrite)) && <Link to={`/admin/documents/${documentId}`}>管理此文档</Link>}<Button disabled={documentState.exclusive || !canUploadVersion} onClick={() => setUpload(true)}>上传新版本</Button>
          <Button disabled={!versionId || !canRead} onClick={() => setPreview(`${path}/preview`)}>预览选中版本</Button>
          {versionId && <DownloadButton key={versionKey} path={`${path}/download`} name={version?.fileName ?? detail.title} disabled={!capabilities.data?.canDownload || !canRead || !currentStatus?.capabilities.canDownload} onDenied={downloadDenied} />}
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
      {versionId && <><VersionReviewPanel key={`review:${versionKey}`} documentId={documentId} versionId={versionId} files={false} onStatus={receiveStatus} onDenied={loseVersion}
        onChanged={() => { setPreview(null); query.reload(); capabilities.reload(); setRevision(value => value + 1); }} />
        {canRead && <ProgressCard key={`progress:${documentId}/${versionId}/${revision}`} documentId={documentId} versionId={versionId} revision={revision} onDenied={loseVersion} />}
        <VersionComments key={`${documentId}/${versionId}`} documentId={documentId} versionId={versionId} status={currentStatus} /></>}
      {!versionId && <p>默认读取当前发布版本；查看候选时请从可见版本列表明确选择。</p>}
      <section className="panel"><h2>版本历史</h2><Table<Version> rowKey="id" pagination={false} dataSource={versions} columns={[
        { title: '版本', render: (_, value) => <Button type="link" onClick={() => { if (value.id === versionId) return; liveVersion.current = ''; setVersionStatus(undefined); setPreview(null); setSelected(value.id); }}>v{value.versionNo}{value.current ? ' · 当前发布' : ''}</Button> },
        { title: '文件名', dataIndex: 'fileName' }, { title: '处理状态', render: (_, value) => progressLabel(value) },
      ]} /></section>
    </>}
    <UploadDialog open={upload} documentId={documentId}
      access={{ key: `${factsKey}/${query.revision}/${capabilities.revision}`, canUploadVersion, checking: factsLoading }}
      onDenied={refreshFacts} onRecheck={recheck} onClose={() => setUpload(false)}
      onUploaded={result => { setSelected(result.versionId); setRevision(value => value + 1); query.reload(); }} />
    <VersionPreview path={detail && canRead ? preview : null} fileName={version?.fileName} onClose={() => setPreview(null)} onDenied={loseVersion} />
  </>;
}
