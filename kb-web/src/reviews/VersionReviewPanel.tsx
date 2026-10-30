import { useEffect, useRef, useState } from 'react';
import { Alert, Button, Space, Tag } from 'antd';
import { Link } from 'react-router-dom';
import { useSession } from '../SessionProvider';
import { useDocumentOperations } from '../documents/OperationContext';
import { useResource } from '../useResource';
import { useAdminWrite } from '../admin/hooks';
import { AdminModal } from '../admin/ui';
import { Feedback } from '../components';
import { DownloadButton, FilePreview } from '../FileAccess';
import { reviewIntent, reviewPaths, reviewWrite, type ReviewCommand, type ReviewReceipt, type ReviewState, type ReviewStatus } from './api';
import { useReviewScope } from './lifecycle';

export function reviewLabel(state: Pick<ReviewState, 'indexStatus' | 'reviewState' | 'publicationEvidence' | 'waitingReason' | 'policy'>): string {
  if (state.publicationEvidence === 'PUBLISHED' || state.publicationEvidence === 'OBSERVED_CURRENT') return '已发布';
  if (state.waitingReason === 'INDEX_REFRESH_REQUIRED') return '索引待刷新';
  if (state.waitingReason === 'EMBEDDING_MAINTENANCE') return '维护等待';
  if (state.indexStatus !== 'indexed') return ({ pending: '排队中', parsing: '处理中', failed: '解析失败' } as Record<string, string>)[state.indexStatus] ?? '处理中';
  return ({ IN_REVIEW: '审核中', REJECTED: '已驳回 · 待重新送审', WITHDRAWN: '已撤回 · 待重新送审', CANCELLED: '审核已取消 · 待重新送审' } as Record<string, string>)[state.reviewState]
    ?? (state.policy === 'REVIEW_REQUIRED' ? '待送审' : '已索引 · 尚未发布');
}
const commands: [ReviewCommand, keyof ReviewStatus['capabilities'], string][] = [
  ['submit', 'canSubmit', '手动送审'], ['approve', 'canApprove', '通过并发布'], ['reject', 'canReject', '驳回'],
  ['withdraw', 'canWithdraw', '撤回本轮'], ['refresh', 'canRefresh', '刷新候选索引'],
];
export function VersionReviewPanel({ documentId, versionId, onChanged, onStatus, onDenied, files = true }: {
  documentId: string; versionId: string; onChanged?: () => void; onStatus?: (value: ReviewStatus | undefined) => void; onDenied?: () => void; files?: boolean;
}) {
  const scope = useReviewScope();
  return scope.active ? <ReviewContext key={`${scope.key}:${documentId}/${versionId}`} documentId={documentId} versionId={versionId} onChanged={onChanged} onStatus={onStatus} onDenied={onDenied} files={files} />
    : <Alert type="info" title="版本内容已暂停，等待当前身份确认。" />;
}
function ReviewContext({ documentId, versionId, onChanged, onStatus, onDenied, files }: {
  documentId: string; versionId: string; onChanged?: () => void; onStatus?: (value: ReviewStatus | undefined) => void; onDenied?: () => void; files: boolean;
}) {
  const { session, state } = useSession();
  const { state: documentState } = useDocumentOperations();
  const query = useResource(signal => session.json<ReviewStatus>(reviewPaths.status(documentId, versionId), { signal }), `${state.epoch}:${documentId}/${versionId}`);
  const current = !query.loading && !query.error ? query.data : undefined;
  const [opinions, setOpinions] = useState(false), [preview, setPreview] = useState(false);
  const [chosen, setChosen] = useState<{ command: ReviewCommand; state: ReviewState } | null>(null);
  const [receipt, setReceipt] = useState('');
  const denied = useRef(onDenied); denied.current = onDenied;
  useEffect(() => { if (query.status && [401, 403, 404].includes(query.status)) denied.current?.(); }, [query.status]);
  useEffect(() => { onStatus?.(current); }, [current, onStatus]);
  useEffect(() => {
    if (!current?.capabilities.canReadOpinions) setOpinions(false);
    if (!current?.capabilities.canRead) setPreview(false);
    if (query.error) { setChosen(null); setReceipt(''); }
  }, [current, query.error]);
  const refresh = () => { setOpinions(false); setPreview(false); query.reload(); };
  const filePath = `/api/documents/${documentId}/versions/${versionId}`;
  return <section className="panel"><h2>版本审核状态</h2><Feedback {...query} retry={refresh} />
    <Button onClick={refresh}>重新核对版本</Button>
    {receipt && <Alert type="success" title={receipt} />}
    {current && <><p><Tag>{reviewLabel(current.state)}</Tag>固定策略：{current.state.policy === 'REVIEW_REQUIRED' ? '需审核' : '自动发布'} · 本轮：{current.state.roundNo ?? '尚未送审'}</p>
      {current.capabilities.canRead && <p>当前发布版本：{current.state.currentVersionId ?? '尚无版本'}</p>}
      {!current.capabilities.canRead && <p>当前仅可查看本人最小状态；没有正文、文件或私有意见读取资格。</p>}
      <Space wrap>{commands.filter(([, ability]) => current.capabilities[ability]).map(([command, , label]) =>
        <Button key={command} aria-label={label} disabled={documentState.exclusive} onClick={() => setChosen({ command, state: { ...current.state } })}>{label}</Button>)}
        {files && current.capabilities.canRead && <><Link to={`/documents/${documentId}`}>打开文档</Link><Button onClick={() => setPreview(true)}>预览此版本</Button></>}
        {files && current.capabilities.canRead && current.capabilities.canDownload && <DownloadButton path={`${filePath}/download`} name={`版本-${current.state.versionNo}`} onDenied={refresh} />}
        {current.capabilities.canReadOpinions && <Button onClick={() => setOpinions(value => !value)}>{opinions ? '关闭私有意见' : '查看私有意见'}</Button>}
      </Space>
      {opinions && current.capabilities.canReadOpinions && <Opinions key={`${documentId}/${versionId}`} documentId={documentId} versionId={versionId} onDenied={refresh} />}
    </>}
    {chosen && <CommandDialog key={`${documentId}/${versionId}/${chosen.command}`} command={chosen.command} target={chosen.state}
      current={current} close={() => setChosen(null)} refresh={refresh} done={value => {
        setChosen(null); setReceipt(`操作回执已确认：版本 ${value.versionId}${value.roundNo ? `，轮次 ${value.roundNo}` : ''}。`); refresh(); onChanged?.();
      }} />}
    {files && <FilePreview path={preview && current?.capabilities.canRead ? `${filePath}/preview` : null} onClose={() => setPreview(false)} onDenied={refresh} />}
  </section>;
}
function Opinions({ documentId, versionId, onDenied }: { documentId: string; versionId: string; onDenied: () => void }) {
  const { session } = useSession(), [cursor, setCursor] = useState<string | null>(null);
  const query = useResource(signal => session.json<ReviewStatus>(reviewPaths.status(documentId, versionId, true, cursor), { signal }), `${documentId}/${versionId}/opinions/${cursor}`);
  const denied = useRef(onDenied); denied.current = onDenied;
  useEffect(() => { if (query.status && [401, 403, 404].includes(query.status)) denied.current(); }, [query.status]);
  return <section aria-label="私有审核意见"><h3>私有审核意见</h3><Feedback {...query} retry={query.reload} />
    {query.data?.rounds.map(round => <article key={round.reviewId}><p>第 {round.roundNo} 轮 · {round.state}</p><p style={{ whiteSpace: 'pre-wrap' }}>{round.reason ?? '本轮无驳回意见'}</p></article>)}
    {query.data?.hasMore && query.data.nextCursor && <Button onClick={() => setCursor(query.data!.nextCursor)}>下一页意见</Button>}
  </section>;
}
function CommandDialog({ command, target, current, close, refresh, done }: {
  command: ReviewCommand; target: ReviewState; current: ReviewStatus | undefined; close: () => void; refresh: () => void; done: (receipt: ReviewReceipt) => void;
}) {
  const write = useAdminWrite(`${target.documentId}/${target.versionId}/${target.reviewId ?? 'draft'}/${command}`, reviewWrite);
  const [reason, setReason] = useState(''), [conflict, setConflict] = useState(false);
  const [, ability, title] = commands.find(([value]) => value === command)!;
  const allowed = !!current && current.capabilities[ability] === true
    && current.state.documentId === target.documentId && current.state.versionId === target.versionId
    && (current.state.reviewId ?? null) === (target.reviewId ?? null) && (current.state.roundNo ?? null) === (target.roundNo ?? null)
    && String(current.state.stateVersion) === String(target.stateVersion);
  const invalidated = !!current && !allowed;
  // 重读暂未完成时仅暂停；已确认失效的窗口必须关闭重选，不能随旧资格恢复而复活。
  useEffect(() => { if (invalidated) setConflict(true); }, [invalidated]);
  const disabled = !allowed || conflict || write.blocked;
  const receive = (result: Awaited<ReturnType<typeof write.submit<ReviewReceipt>>>) => {
    if (result.status === 'confirmed') done(result.value);
    else if (result.status === 'rejected' && [403, 404, 409].includes(result.error.status)) { setConflict(true); refresh(); }
  };
  const verify = () => { write.reset(); setConflict(true); refresh(); };
  return <AdminModal title={`确认${title}`} write={write} onClose={close}>
    <p>文档 {target.documentId} · v{target.versionNo} · 轮次 {target.roundNo ?? '尚未送审'}</p>
    {command === 'approve' && <Alert type="warning" title="通过即发布，并替换当前发布版本。" />}
    {command === 'refresh' && <Alert type="warning" title="将重新解析候选并取消旧活动审核；完成后必须重新送审。" />}
    {write.message && <Alert type={write.phase === 'uncertain' ? 'warning' : 'error'} title={write.message} />}
    {write.phase === 'uncertain' && <Space><Button disabled={write.blocked} onClick={() => void write.retry<ReviewReceipt>().then(receive)}>按原内容重试</Button><Button onClick={verify}>重新读取核对</Button></Space>}
    {(conflict || invalidated) && <Alert type="info" title={`当前操作资格或版本状态已变化；本窗口不会把决定应用到新状态或新轮次。${write.phase === 'uncertain' ? '仅可按原内容重试或重新读取核对。' : '请关闭后重新选择操作。'}`} />}
    {command === 'reject' && <label>私有驳回原因<textarea aria-label="私有驳回原因" value={reason} disabled={disabled || write.pending} onChange={event => setReason(event.target.value)} /></label>}
    <p>取消浏览器请求或关闭页面不会撤销服务器已执行的操作。</p>
    <Button type="primary" disabled={disabled || write.pending || (command === 'reject' && !reason.trim())}
      onClick={() => { if (!disabled && !write.pending) void write.submit<ReviewReceipt>(() => reviewIntent(command, target, reason)).then(receive); }}>确认{title}</Button>
  </AdminModal>;
}
