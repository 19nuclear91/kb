import { createContext, useCallback, useContext, useEffect, useRef, useState } from 'react';
import { Alert, Button, Modal, Space, Tag } from 'antd';
import { useSession } from '../SessionProvider';
import { useDocumentOperations } from '../documents/OperationContext';
import { useResource } from '../useResource';
import { inFlight, useAdminWrite, type Binding, type WriteBindings } from '../admin/hooks';
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
const composerScope = (documentId: string, versionId: string, parent: string | null) => `comment:${documentId}/${versionId}/${parent ?? 'root'}`;

/**
 * 页面内的共享状态：资源拒绝信号；当前身份下已知的本人评论 ID（不含正文），仅用于撤权后的无正文删除；
 * 结果不确定的写绑定（由面板持有者保存，收起回复或重读失败卸载输入框后仍可按原标识核对）；
 * 资格重核轮次（任一入口被拒绝即重读根列表与已展开的回复，期间暂停所有发表）。
 */
interface Thread {
  documentId: string; versionId: string; deny: () => void; own: Set<string>;
  /** 打开删除确认时调用：同一输入框中正文与被删评论相同、已待核对（不在途）的绑定，视为该评论的本地副本。 */
  copies: (origin: { parentId: string | null; body: string }) => readonly Binding[];
  /** 本人删除确认后调用；copies 为打开确认时取得的快照，只丢弃其中仍在表中的同一绑定（撤权后删除无正文可比对）。 */
  erased: (id: string, copies?: readonly Binding[]) => void;
  writes: WriteBindings; round: number; recheck: () => void;
}
const ThreadContext = createContext<Thread | null>(null);
function useThread() {
  const thread = useContext(ThreadContext);
  if (!thread) throw new Error('缺少评论上下文');
  return thread;
}

/**
 * 文档详情所选固定版本的讨论入口：只有当前状态确认该版本有发布证据且可读时才挂载评论面板；
 * 新未发布版本或仅有旧兼容标记的版本显示不可评论说明，不请求评论正文。
 * 版本状态重读期间面板卸载、不显示旧讨论，但未决写绑定保留到状态重新确认；确认不可评论即丢弃。
 */
export function VersionComments({ documentId, versionId, status }: { documentId: string; versionId: string; status: ReviewStatus | undefined }) {
  const [writes] = useState<WriteBindings>(() => new Map());
  const matched = status && status.state.documentId === documentId && status.state.versionId === versionId ? status : undefined;
  const published = matched?.state.publicationEvidence === 'PUBLISHED' || matched?.state.publicationEvidence === 'OBSERVED_CURRENT';
  const eligible = published && matched?.capabilities.canRead === true;
  useEffect(() => { if (matched && !eligible) writes.clear(); }, [matched, eligible, writes]);
  if (!matched) return null;
  if (!published) return <section className="panel" aria-label="版本评论"><h2>版本评论</h2>
    <p className="muted">该版本尚无发布证据，不开放评论；审核意见使用独立的私有区域。</p></section>;
  if (!eligible) return null;
  return <CommentPanel documentId={documentId} versionId={versionId} versionLabel={`v${matched.state.versionNo}`} bindings={writes} />;
}

/**
 * 固定版本评论面板：隐藏、身份或目标变化即卸载全部正文、草稿、游标与写意图；恢复须先核对身份。
 * bindings 由持有者跨面板重挂载保存未决写绑定；未传入时随面板卸载丢弃。持有者须让面板随会话世代重挂载（key 含 epoch），
 * 旧世代条目另由 useAdminWrite 清理。onDenied 通知持有者资源已不可访问，此时由持有者负责重新核对。
 */
export function CommentPanel({ documentId, versionId, versionLabel, bindings, onDenied }: {
  documentId: string; versionId: string; versionLabel?: string; bindings?: WriteBindings; onDenied?: () => void;
}) {
  const scope = useReviewScope();
  const [local] = useState<WriteBindings>(() => new Map());
  const writes = bindings ?? local;
  useEffect(() => { if (!scope.active) writes.clear(); }, [scope.active, writes]);
  return scope.active ? <CommentContext key={`${scope.key}:${documentId}/${versionId}`} documentId={documentId} versionId={versionId}
    versionLabel={versionLabel ?? versionId} writes={writes} onDenied={onDenied} />
    : <section className="panel" aria-label="版本评论"><Alert type="info" title="评论已暂停，等待当前身份确认。" /></section>;
}

function CommentContext({ documentId, versionId, versionLabel, writes, onDenied }: {
  documentId: string; versionId: string; versionLabel: string; writes: WriteBindings; onDenied?: () => void;
}) {
  const [denied, setDenied] = useState(false), [generation, setGeneration] = useState(0), [round, setRound] = useState(0);
  const own = useRef(new Set<string>());
  const [erased, setErased] = useState<ReadonlySet<string>>(() => new Set());
  const notify = useRef(onDenied); notify.current = onDenied;
  // 资源拒绝：清除受保护内容与未决写绑定，只保留已知本人评论 ID 供无正文删除。
  const deny = useCallback(() => { writes.clear(); setDenied(true); notify.current?.(); }, [writes]);
  const recheck = useCallback(() => setRound(value => value + 1), []);
  // 被删评论可能正是某次结果不确定的发表，同一输入框中正文相同、已待核对的绑定是它的本地副本。
  // 只在打开删除确认时取快照：此时仍在途、或之后才变为不确定的发表，无法据正文判定为同一条，保留并照常显示待核对
  // （即便正是同一条，按原标识重放也只会得到“原评论已删除”的回执）。
  const copies = useCallback((origin: { parentId: string | null; body: string }) => {
    const source = `|${composerScope(documentId, versionId, origin.parentId)}`;
    return [...writes].filter(([key, entry]) => key.endsWith(source) && entry.intent.body?.body === origin.body && !inFlight(entry))
      .map(([, entry]) => entry);
  }, [documentId, versionId, writes]);
  const erase = useCallback((id: string, snapshot: readonly Binding[] = []) => {
    own.current.delete(id);
    // 本人删除根评论后，其下回复的未决绑定不再可提交，与草稿一并清除。
    const suffix = `|${composerScope(documentId, versionId, id)}`;
    // 快照中的绑定若在确认期间被重新发出，结果又未知，同样保留。
    for (const [key, entry] of writes) {
      if (key.endsWith(suffix) || (snapshot.includes(entry) && !inFlight(entry))) writes.delete(key);
    }
    setErased(previous => new Set([...previous, id]));
  }, [documentId, versionId, writes]);
  const thread: Thread = { documentId, versionId, deny, own: own.current, copies, erased: erase, writes, round, recheck };
  const recover = () => { setDenied(false); setGeneration(value => value + 1); };
  return <ThreadContext value={thread}><section className="panel" aria-label="版本评论">
    <h2>版本评论 · 固定版本 {versionLabel}</h2>
    <p className="muted">评论只属于该版本，不会随新版本发布迁移；内容按纯文本显示，不执行链接或图片。</p>
    {denied ? <Denied erased={erased} onRecover={onDenied ? undefined : recover} />
      : <Roots key={generation} erased={erased} />}
  </section></ThreadContext>;
}

function Roots({ erased }: { erased: ReadonlySet<string> }) {
  const { session } = useSession(), thread = useThread();
  const list = useCommentList((cursor, signal) => rootPage(session, thread.documentId, thread.versionId, cursor, signal),
    `${thread.documentId}/${thread.versionId}/roots`);
  useDenial(list, thread.deny);
  useOwn(list.items, thread.own);
  const rechecking = useRecheck(list, thread.round);
  return <>
    <ListStatus list={list} empty="该版本暂无评论。" retryLabel="重新读取评论" />
    {list.loaded && (list.canCreate ? <Composer parent={null} label="评论内容" hold={holdReason(list, rechecking)} onSettled={list.reload} />
      : <p className="muted">当前可以阅读该版本的评论，但没有发表评论的资格。</p>)}
    {list.items.map(item => <RootItem key={item.commentId} item={item} erased={erased} list={list} />)}
    <More list={list} label="更多评论" end={list.items.length > 0 ? '已显示全部可见根评论。' : undefined} retryLabel="重新读取评论" />
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
  const rechecking = useRecheck(list, thread.round);
  return <section className="comment-replies" aria-label="回复列表">
    <ListStatus list={list} empty="暂无回复。" retryLabel="重新读取回复" />
    {list.items.map(item => <article key={item.commentId} className="comment" aria-label={`回复 ${item.commentId}`}>
      <CommentBody item={item} erased={erased} onErased={() => list.tombstone(item.commentId)} /></article>)}
    <More list={list} label="更多回复" end={list.items.length > 0 ? '已显示全部可见回复。' : undefined} retryLabel="重新读取回复" />
    {list.loaded && !rootDeleted && list.canCreate && <Composer parent={root} label="回复内容" hold={holdReason(list, rechecking)} onSettled={list.reload} />}
    {list.loaded && rootDeleted && <p className="muted">根评论已删除，已有回复保留，不能追加新回复。</p>}
  </section>;
}

/** 正文按纯文本渲染；墓碑不显示原正文或作者。删除入口只给本人，管理资格不会出现删除按钮。 */
function CommentBody({ item, erased, onErased }: { item: CommentItem; erased: ReadonlySet<string>; onErased: () => void }) {
  const { state: documentState } = useDocumentOperations();
  // 非空即确认框打开；内容为打开时取得的同内容待核对绑定快照。
  const thread = useThread(), [copies, setCopies] = useState<readonly Binding[] | null>(null);
  const gone = item.deleted || erased.has(item.commentId);
  if (gone) return <p className="muted">该评论已删除 · {when(item.createdAt)}</p>;
  return <>
    <div className="comment-meta"><strong>{item.author!.label}</strong>{item.isOwn && <Tag>本人</Tag>}<span className="muted">{when(item.createdAt)}</span></div>
    <pre className="content-text">{item.body}</pre>
    {item.canDelete && <Button size="small" danger disabled={documentState.exclusive}
      onClick={() => setCopies(thread.copies({ parentId: item.parentId, body: item.body! }))}>删除评论</Button>}
    {copies && <DeleteDialog id={item.commentId} onClose={() => setCopies(null)}
      onDone={() => { setCopies(null); thread.erased(item.commentId, copies); onErased(); }} />}
  </>;
}

/**
 * 暂停发表的原因：资格重新确认中（含已请求、尚未开始的重核），或最近一次读取未完成
 * （续页失败及其重试在途期间都不沿用已确认的发表能力）。空串表示可提交。
 */
function holdReason(list: CommentList, rechecking: boolean): string {
  if (rechecking || list.revalidating) return '正在重新核对发表资格…';
  // 续页故障与重试入口在列表末尾（原续页位置），提示指向那里。
  if (list.failed) return '读取未完成，请在列表末尾重新读取后再发表。';
  if (list.incomplete) return '正在重新读取，完成后才能发表。';
  return '';
}

/** hold 非空时不发送新提交或重试，避免沿用旧的发表能力；未决绑定存于 thread.writes，输入框卸载后仍可核对。 */
function Composer({ parent, label, hold, onSettled }: { parent: string | null; label: string; hold: string; onSettled: () => void }) {
  const thread = useThread();
  const write = useAdminWrite(composerScope(thread.documentId, thread.versionId, parent), commentWrite, thread.writes);
  const [draft, setDraft] = useState(''), [notice, setNotice] = useState('');
  const blocked = hold !== '' || write.blocked;
  const action = parent === null ? '发表评论' : '提交回复';
  const length = commentLength(draft), valid = length >= 1 && length <= COMMENT_MAX && !/\u0000/.test(draft);
  const receive = (result: Awaited<ReturnType<typeof write.submit<CommentReceipt>>>) => {
    if (result.status === 'confirmed') {
      setDraft('');
      setNotice(result.value.deleted ? '原评论已由本人删除，未重新发表。' : result.value.replayed ? '已核对为同一次提交，未重复发表。' : '已发表。');
      onSettled();
    } else if (result.status === 'rejected') {
      setNotice('');
      // 404 资源不可访问；403/409 资格或结构已变化，重读根列表与已展开的回复，确认前暂停所有发表。
      if (result.error.status === 404) thread.deny();
      else if (result.error.status === 403 || result.error.status === 409) thread.recheck();
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
      {blocked && <span role="status" className="muted">{hold || '正在确认删除文档，暂不能发表。'}</span>}
      <Button type="primary" disabled={!valid || write.pending || blocked} onClick={submit}>{action}</Button></Space>
  </div>;
}

function DeleteDialog({ id, onClose, onDone }: { id: string; onClose: () => void; onDone: () => void }) {
  const thread = useThread();
  const write = useAdminWrite(`comment-delete:${thread.documentId}/${thread.versionId}/${id}`, commentWrite, thread.writes);
  const [missing, setMissing] = useState(false);
  const receive = (result: Awaited<ReturnType<typeof write.submit<void>>>) => {
    if (result.status === 'confirmed') onDone();
    else if (result.status === 'rejected' && result.error.status === 404) setMissing(true);
  };
  return <Modal title="删除本人评论" open footer={null} destroyOnHidden closable={!write.busy} mask={{ closable: !write.busy }}
    onCancel={() => { if (!write.busy) onClose(); }}>
    <p>删除后正文立即清除，无法恢复；他人的回复不受影响。</p>
    <WriteNotice write={write} disabled={write.blocked} onRetry={() => void write.retry<void>().then(receive)} />
    {missing && <Alert type="info" title="该评论已不存在或不属于当前账号。" />}
    <Button danger type="primary" disabled={write.pending || missing || write.blocked}
      onClick={() => void write.submit<void>(() => deleteIntent(thread.documentId, thread.versionId, id)).then(receive)}>确认删除</Button>
  </Modal>;
}

/**
 * 资源已不可访问：清除全部受保护内容，最多保留当前身份已知本人评论的无正文删除动作；不重读讨论，也不提供代删。
 * onRecover 为空时由持有者统一重新核对（如通知目标），这里不另设入口，避免绕过持有者的清理。
 */
function Denied({ erased, onRecover }: { erased: ReadonlySet<string>; onRecover?: () => void }) {
  const { state: documentState } = useDocumentOperations();
  const thread = useThread(), [target, setTarget] = useState<string | null>(null);
  const known = [...thread.own].filter(id => !erased.has(id));
  return <>
    <Alert type="warning" title="该版本的评论当前不可访问，已清理页面中的评论内容。"
      action={onRecover && <Button onClick={onRecover}>重新核对</Button>} />
    {known.length > 0 && <><p className="muted">仍可删除本页已知的本人评论；不会显示正文或重新读取讨论。</p>
      <ul className="admin-list">{known.map(id => <li key={id}><span>本人评论 {id.slice(0, 8)}</span>
        <Button size="small" danger disabled={documentState.exclusive} onClick={() => setTarget(id)}>删除本人评论</Button></li>)}</ul></>}
    {target && <DeleteDialog id={target} onClose={() => setTarget(null)} onDone={() => { thread.erased(target); setTarget(null); }} />}
  </>;
}

/** 可重试的读取故障；拒绝类状态由 useDenial 转入 Denied，这里不显示。 */
function Failure({ list, retryLabel }: { list: CommentList; retryLabel: string }) {
  if (DENIED.has(list.status ?? 0)) return null;
  return <Alert type="error" title={failureText(list.status)} action={<Button onClick={list.retry}>{retryLabel}</Button>} />;
}
/** 列表顶部状态：首页读取中、首页失败或空列表。续页失败由 More 在续页位置显示。 */
function ListStatus({ list, empty, retryLabel }: { list: CommentList; empty: string; retryLabel: string }) {
  if (list.loading && !list.loaded) return <p role="status" className="muted">正在读取…</p>;
  if (list.failed && !list.loaded) return <Failure list={list} retryLabel={retryLabel} />;
  if (list.loaded && !list.items.length && !list.hasMore) return <p className="muted">{empty}</p>;
  return null;
}
/**
 * 续页入口。续页失败时在原位置显示故障与“重新读取”（按重试策略恢复），不再提供续页按钮，
 * 也不显示“已显示全部”。
 */
function More({ list, label, end, retryLabel }: { list: CommentList; label: string; end?: string; retryLabel: string }) {
  if (list.failed) return list.loaded ? <Failure list={list} retryLabel={retryLabel} /> : null;
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
 * 资格重核轮次变化即重读本列表首页；重读期间 revalidating 为真，发表暂停。
 * 重读要到 effect 才开始，返回值在渲染期就标记“轮次已变、重读未开始”，使拒绝提交到 DOM 的那一次渲染也暂停发表。
 */
function useRecheck(list: CommentList, round: number): boolean {
  const seen = useRef(round), reload = list.reload;
  useEffect(() => {
    if (seen.current === round) return;
    seen.current = round;
    reload();
  }, [round, reload]);
  return seen.current !== round;
}

/**
 * 通知打开的固定评论目标：先按原版本核对目标评论，不以最新版本或其他版本的讨论替代；
 * 失效时清理并提示，不显示其他内容。展开的本版本讨论被拒绝时，目标正文与作者一并卸载。
 */
export function CommentTargetPanel({ documentId, versionId, commentId }: { documentId: string; versionId: string; commentId: string }) {
  const scope = useReviewScope();
  return scope.active ? <TargetContext key={`${scope.key}:${documentId}/${versionId}/${commentId}`} documentId={documentId} versionId={versionId} commentId={commentId} />
    : <Alert type="info" title="评论目标已暂停，等待当前身份确认。" />;
}
function TargetContext({ documentId, versionId, commentId }: { documentId: string; versionId: string; commentId: string }) {
  const [writes] = useState<WriteBindings>(() => new Map());
  const [thread, setThread] = useState(false), [hidden, setHidden] = useState(false), [check, setCheck] = useState(0);
  const hide = useCallback(() => setHidden(true), []);
  const recover = () => { setHidden(false); setThread(false); setCheck(value => value + 1); };
  return <section aria-label="评论目标">
    {hidden ? <Alert type="warning" title="本版本讨论当前不可访问，已清理评论目标内容；不会改为显示其他版本的讨论。"
      action={<Button onClick={recover}>重新核对目标</Button>} />
      : <TargetDetail key={check} documentId={documentId} versionId={versionId} commentId={commentId} thread={thread}
        onToggle={() => setThread(value => !value)} />}
    {thread && <CommentPanel documentId={documentId} versionId={versionId} bindings={writes} onDenied={hide} />}
  </section>;
}
function TargetDetail({ documentId, versionId, commentId, thread, onToggle }: {
  documentId: string; versionId: string; commentId: string; thread: boolean; onToggle: () => void;
}) {
  const { session, state } = useSession();
  const query = useResource(signal => commentDetail(session, documentId, versionId, commentId, signal), `${state.epoch}:${documentId}/${versionId}/${commentId}`);
  if (query.loading) return <p role="status" className="muted">正在核对评论目标…</p>;
  if (query.status && DENIED.has(query.status))
    return <Alert type="warning" title="评论目标当前不可访问，已清理旧内容；不会改为显示其他版本的讨论。" />;
  if (query.error || !query.data) return <Alert type="error" title={failureText(query.status)} action={<Button onClick={query.reload}>重新核对目标</Button>} />;
  const { item, canReply } = query.data;
  return <>
    <p>固定版本 {versionId} · {item.parentId ? `回复（所属根评论 ${item.parentId.slice(0, 8)}）` : '根评论'}</p>
    {item.deleted ? <p className="muted">该评论已删除。</p>
      : <><div className="comment-meta"><strong>{item.author!.label}</strong><span className="muted">{when(item.createdAt)}</span></div>
        <pre className="content-text">{item.body}</pre></>}
    {!canReply && <p className="muted">当前不能在该讨论下追加回复。</p>}
    <Button onClick={onToggle}>{thread ? '收起本版本讨论' : '查看本版本讨论'}</Button>
  </>;
}
