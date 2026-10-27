import { createContext, useCallback, useContext, useEffect, useRef, useState } from 'react';
import { Alert, Button, Modal, Space, Tag } from 'antd';
import { useSession } from '../SessionProvider';
import { useResource } from '../useResource';
import { useAdminWrite } from '../admin/hooks';
import { WriteNotice } from '../admin/ui';
import { useReviewScope } from '../reviews/lifecycle';
import type { ReviewStatus } from '../reviews/api';
import { COMMENT_MAX, commentDetail, commentLength, commentWrite, createIntent, deleteIntent, replyPage, rootPage,
  type CommentItem, type CommentReceipt } from './api';
import { useCommentList, type CommentList } from './hooks';

const DENIED = new Set([401, 403, 404]);
const failureText = (status?: number) => status === 400 ? '评论请求不符合要求，请刷新后重试。'
  : '评论读取失败，暂不能确认讨论内容；这不表示该版本没有评论。';
const when = (value: string) => new Date(value).toLocaleString('zh-CN');

/** 页面内的共享状态：资源拒绝信号与当前身份下已知的本人评论 ID（不含正文），仅用于撤权后的无正文删除。 */
interface Thread { documentId: string; versionId: string; deny: () => void; own: Set<string>; erased: (id: string) => void }
const ThreadContext = createContext<Thread | null>(null);
function useThread() {
  const thread = useContext(ThreadContext);
  if (!thread) throw new Error('缺少评论上下文');
  return thread;
}

/**
 * 文档详情所选固定版本的讨论入口：只有当前状态确认该版本有发布证据且可读时才挂载评论面板；
 * 新未发布版本或仅有旧兼容标记的版本显示不可评论说明，不请求评论正文。
 */
export function VersionComments({ documentId, versionId, status }: { documentId: string; versionId: string; status: ReviewStatus | undefined }) {
  if (!status || status.state.documentId !== documentId || status.state.versionId !== versionId) return null;
  const published = status.state.publicationEvidence === 'PUBLISHED' || status.state.publicationEvidence === 'OBSERVED_CURRENT';
  if (!published) return <section className="panel" aria-label="版本评论"><h2>版本评论</h2>
    <p className="muted">该版本尚无发布证据，不开放评论；审核意见使用独立的私有区域。</p></section>;
  if (!status.capabilities.canRead) return null;
  return <CommentPanel documentId={documentId} versionId={versionId} />;
}

/** 固定版本评论面板：隐藏、身份或目标变化即卸载全部正文、草稿、游标与写意图；恢复须先核对身份。 */
export function CommentPanel({ documentId, versionId }: { documentId: string; versionId: string }) {
  const scope = useReviewScope();
  return scope.active ? <CommentContext key={`${scope.key}:${documentId}/${versionId}`} documentId={documentId} versionId={versionId} />
    : <section className="panel" aria-label="版本评论"><Alert type="info" title="评论已暂停，等待当前身份确认。" /></section>;
}

function CommentContext({ documentId, versionId }: { documentId: string; versionId: string }) {
  const [denied, setDenied] = useState(false), [generation, setGeneration] = useState(0);
  const own = useRef(new Set<string>());
  const [erased, setErased] = useState<ReadonlySet<string>>(() => new Set());
  const deny = useCallback(() => setDenied(true), []);
  const erase = useCallback((id: string) => { own.current.delete(id); setErased(previous => new Set([...previous, id])); }, []);
  const thread: Thread = { documentId, versionId, deny, own: own.current, erased: erase };
  const recover = () => { setDenied(false); setGeneration(value => value + 1); };
  return <ThreadContext value={thread}><section className="panel" aria-label="版本评论">
    <h2>版本评论 · 固定版本 {versionId}</h2>
    <p className="muted">评论只属于该版本，不会随新版本发布迁移；内容按纯文本显示，不执行链接或图片。</p>
    {denied ? <Denied erased={erased} onRecover={recover} />
      : <Roots key={generation} erased={erased} />}
  </section></ThreadContext>;
}

function Roots({ erased }: { erased: ReadonlySet<string> }) {
  const { session } = useSession(), thread = useThread();
  const list = useCommentList((cursor, signal) => rootPage(session, thread.documentId, thread.versionId, cursor, signal),
    `${thread.documentId}/${thread.versionId}/roots`);
  useDenial(list, thread.deny);
  useOwn(list.items, thread.own);
  return <>
    <ListStatus list={list} empty="该版本暂无评论。" retryLabel="重新读取评论" />
    {list.loaded && (list.canCreate ? <Composer parent={null} label="评论内容" blocked={list.revalidating} onSettled={list.reload} />
      : <p className="muted">当前可以阅读该版本的评论，但没有发表评论的资格。</p>)}
    {list.items.map(item => <RootItem key={item.commentId} item={item} erased={erased} list={list} />)}
    <More list={list} label="更多评论" end={list.items.length > 0 ? '已显示全部可见根评论。' : undefined} />
  </>;
}

function RootItem({ item, erased, list }: { item: CommentItem; erased: ReadonlySet<string>; list: CommentList }) {
  const [open, setOpen] = useState(false);
  const gone = item.deleted || erased.has(item.commentId);
  return <article className="comment" aria-label={`根评论 ${item.commentId}`}>
    <CommentBody item={item} erased={erased} onErased={() => list.tombstone(item.commentId)} />
    <Button size="small" onClick={() => setOpen(value => !value)}>{open ? '收起回复' : '查看回复'}</Button>
    {/* 根删除状态变化即重建回复区：清理回复草稿并重新读取当前资格。 */}
    {open && <Replies key={`${item.commentId}:${gone}`} root={item.commentId} rootDeleted={gone} erased={erased} />}
  </article>;
}

function Replies({ root, rootDeleted, erased }: { root: string; rootDeleted: boolean; erased: ReadonlySet<string> }) {
  const { session } = useSession(), thread = useThread();
  const list = useCommentList((cursor, signal) => replyPage(session, thread.documentId, thread.versionId, root, cursor, signal),
    `${thread.documentId}/${thread.versionId}/${root}/replies`);
  useDenial(list, thread.deny);
  useOwn(list.items, thread.own);
  return <section className="comment-replies" aria-label="回复列表">
    <ListStatus list={list} empty="暂无回复。" retryLabel="重新读取回复" />
    {list.items.map(item => <article key={item.commentId} className="comment" aria-label={`回复 ${item.commentId}`}>
      <CommentBody item={item} erased={erased} onErased={() => list.tombstone(item.commentId)} /></article>)}
    <More list={list} label="更多回复" end={list.items.length > 0 ? '已显示全部可见回复。' : undefined} />
    {list.loaded && !rootDeleted && list.canCreate && <Composer parent={root} label="回复内容" blocked={list.revalidating} onSettled={list.reload} />}
    {list.loaded && rootDeleted && <p className="muted">根评论已删除，已有回复保留，不能追加新回复。</p>}
  </section>;
}

/** 正文按纯文本渲染；墓碑不显示原正文或作者。删除入口只给本人，管理资格不会出现删除按钮。 */
function CommentBody({ item, erased, onErased }: { item: CommentItem; erased: ReadonlySet<string>; onErased: () => void }) {
  const thread = useThread(), [deleting, setDeleting] = useState(false);
  const gone = item.deleted || erased.has(item.commentId);
  if (gone) return <p className="muted">该评论已删除 · {when(item.createdAt)}</p>;
  return <>
    <div className="comment-meta"><strong>{item.author!.label}</strong>{item.isOwn && <Tag>本人</Tag>}<span className="muted">{when(item.createdAt)}</span></div>
    <pre className="content-text">{item.body}</pre>
    {item.canDelete && <Button size="small" danger onClick={() => setDeleting(true)}>删除评论</Button>}
    {deleting && <DeleteDialog id={item.commentId} onClose={() => setDeleting(false)}
      onDone={() => { setDeleting(false); thread.erased(item.commentId); onErased(); }} />}
  </>;
}

/** blocked：列表正在重新确认资格，期间不发送新提交或重试，避免沿用旧的发表能力。 */
function Composer({ parent, label, blocked, onSettled }: { parent: string | null; label: string; blocked: boolean; onSettled: () => void }) {
  const thread = useThread();
  const write = useAdminWrite(`comment:${thread.documentId}/${thread.versionId}/${parent ?? 'root'}`, commentWrite);
  const [draft, setDraft] = useState(''), [notice, setNotice] = useState('');
  const action = parent === null ? '发表评论' : '提交回复';
  const length = commentLength(draft), valid = length >= 1 && length <= COMMENT_MAX && !/\u0000/.test(draft);
  const receive = (result: Awaited<ReturnType<typeof write.submit<CommentReceipt>>>) => {
    if (result.status === 'confirmed') {
      setDraft('');
      setNotice(result.value.deleted ? '原评论已由本人删除，未重新发表。' : result.value.replayed ? '已核对为同一次提交，未重复发表。' : '已发表。');
      onSettled();
    } else if (result.status === 'rejected') {
      setNotice('');
      if (result.error.status === 404) thread.deny();
      else if (result.error.status === 403 || result.error.status === 409) onSettled();
    }
  };
  const submit = () => {
    if (!valid || write.pending || blocked) return;
    setNotice('');
    void write.submit<CommentReceipt>(() => createIntent(thread.documentId, thread.versionId, parent, draft)).then(receive);
  };
  const retry = () => {
    if (blocked) return;
    void write.retry<CommentReceipt>().then(receive);
  };
  return <div className="comment-composer">
    <WriteNotice write={write} disabled={blocked} onRetry={retry} onVerify={onSettled} />
    {notice && <Alert type="success" title={notice} />}
    <label>{label}<textarea aria-label={label} value={draft} disabled={write.busy}
      onChange={event => { setNotice(''); setDraft(event.target.value); }} /></label>
    <Space><span className="muted" aria-live="polite">{length} / {COMMENT_MAX}</span>
      {length > COMMENT_MAX && <span role="alert">超过 {COMMENT_MAX} 个字符，不会截断，请删减后提交。</span>}
      {blocked && <span role="status" className="muted">正在重新核对发表资格…</span>}
      <Button type="primary" disabled={!valid || write.pending || blocked} onClick={submit}>{action}</Button></Space>
  </div>;
}

function DeleteDialog({ id, onClose, onDone }: { id: string; onClose: () => void; onDone: () => void }) {
  const thread = useThread();
  const write = useAdminWrite(`comment-delete:${thread.documentId}/${thread.versionId}/${id}`, commentWrite);
  const [missing, setMissing] = useState(false);
  const receive = (result: Awaited<ReturnType<typeof write.submit<void>>>) => {
    if (result.status === 'confirmed') onDone();
    else if (result.status === 'rejected' && result.error.status === 404) setMissing(true);
  };
  return <Modal title="删除本人评论" open footer={null} destroyOnHidden closable={!write.busy} mask={{ closable: !write.busy }}
    onCancel={() => { if (!write.busy) onClose(); }}>
    <p>删除后正文立即清除，无法恢复；他人的回复不受影响。</p>
    <WriteNotice write={write} onRetry={() => void write.retry<void>().then(receive)} />
    {missing && <Alert type="info" title="该评论已不存在或不属于当前账号。" />}
    <Button danger type="primary" disabled={write.pending || missing}
      onClick={() => void write.submit<void>(() => deleteIntent(thread.documentId, thread.versionId, id)).then(receive)}>确认删除</Button>
  </Modal>;
}

/** 资源已不可访问：清除全部受保护内容，最多保留当前身份已知本人评论的无正文删除动作；不重读讨论，也不提供代删。 */
function Denied({ erased, onRecover }: { erased: ReadonlySet<string>; onRecover: () => void }) {
  const thread = useThread(), [target, setTarget] = useState<string | null>(null);
  const known = [...thread.own].filter(id => !erased.has(id));
  return <>
    <Alert type="warning" title="该版本的评论当前不可访问，已清理页面中的评论内容。" action={<Button onClick={onRecover}>重新核对</Button>} />
    {known.length > 0 && <><p className="muted">仍可删除本页已知的本人评论；不会显示正文或重新读取讨论。</p>
      <ul className="admin-list">{known.map(id => <li key={id}><span>本人评论 {id.slice(0, 8)}</span>
        <Button size="small" danger onClick={() => setTarget(id)}>删除本人评论</Button></li>)}</ul></>}
    {target && <DeleteDialog id={target} onClose={() => setTarget(null)} onDone={() => { thread.erased(target); setTarget(null); }} />}
  </>;
}

function ListStatus({ list, empty, retryLabel }: { list: CommentList; empty: string; retryLabel: string }) {
  if (list.loading && !list.loaded) return <p role="status" className="muted">正在读取…</p>;
  if (list.failed && !DENIED.has(list.status ?? 0))
    return <Alert type="error" title={failureText(list.status)} action={<Button onClick={list.retry}>{retryLabel}</Button>} />;
  if (list.loaded && !list.items.length && !list.hasMore) return <p className="muted">{empty}</p>;
  return null;
}
function More({ list, label, end }: { list: CommentList; label: string; end?: string }) {
  if (list.hasMore) return <Button loading={list.loading} onClick={list.more}>{label}</Button>;
  return list.loaded && !list.loading && end ? <p className="muted">{end}</p> : null;
}

function useDenial(list: CommentList, deny: () => void) {
  const latest = useRef(deny); latest.current = deny;
  useEffect(() => { if (list.failed && DENIED.has(list.status ?? 0)) latest.current(); }, [list.failed, list.status]);
}
function useOwn(items: CommentItem[], own: Set<string>) {
  useEffect(() => {
    for (const item of items) {
      if (item.isOwn && !item.deleted) own.add(item.commentId);
      else own.delete(item.commentId);
    }
  }, [items, own]);
}

/**
 * 通知打开的固定评论目标：先按原版本核对目标评论，不以最新版本或其他版本的讨论替代；
 * 失效时清理并提示，不显示其他内容。
 */
export function CommentTargetPanel({ documentId, versionId, commentId }: { documentId: string; versionId: string; commentId: string }) {
  const scope = useReviewScope();
  return scope.active ? <TargetContext key={`${scope.key}:${documentId}/${versionId}/${commentId}`} documentId={documentId} versionId={versionId} commentId={commentId} />
    : <Alert type="info" title="评论目标已暂停，等待当前身份确认。" />;
}
function TargetContext({ documentId, versionId, commentId }: { documentId: string; versionId: string; commentId: string }) {
  const { session, state } = useSession();
  const query = useResource(signal => commentDetail(session, documentId, versionId, commentId, signal), `${state.epoch}:${documentId}/${versionId}/${commentId}`);
  const [thread, setThread] = useState(false);
  if (query.loading) return <p role="status" className="muted">正在核对评论目标…</p>;
  if (query.status && DENIED.has(query.status))
    return <Alert type="warning" title="评论目标当前不可访问，已清理旧内容；不会改为显示其他版本的讨论。" />;
  if (query.error || !query.data) return <Alert type="error" title={failureText(query.status)} action={<Button onClick={query.reload}>重新核对目标</Button>} />;
  const { item, canReply } = query.data;
  return <section aria-label="评论目标">
    <p>固定版本 {versionId} · {item.parentId ? `回复（所属根评论 ${item.parentId.slice(0, 8)}）` : '根评论'}</p>
    {item.deleted ? <p className="muted">该评论已删除。</p>
      : <><div className="comment-meta"><strong>{item.author!.label}</strong><span className="muted">{when(item.createdAt)}</span></div>
        <pre className="content-text">{item.body}</pre></>}
    {!canReply && <p className="muted">当前不能在该讨论下追加回复。</p>}
    <Button onClick={() => setThread(value => !value)}>{thread ? '收起本版本讨论' : '查看本版本讨论'}</Button>
    {thread && <CommentPanel documentId={documentId} versionId={versionId} />}
  </section>;
}
