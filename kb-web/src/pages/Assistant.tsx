import { useEffect, useRef, useState } from 'react';
import { Alert, Button, Form, Input, Modal, Space } from 'antd';
import { Link, useNavigate, useParams } from 'react-router-dom';
import { useSession } from '../SessionProvider';
import { useResource } from '../useResource';
import { ApiError, isAbort, messageOf, positiveId } from '../api';
import { bindTurn, mergeTurns, reconcileTurn, type TurnBinding } from '../logic';
import { openTurn } from '../stream';
import { conversationEnabled } from '../navigation';
import { Feedback, PageHeader, References, SafeText, TurnCard } from '../components';
import { CitationDrawer } from '../CitationDrawer';
import type { Citation, Conversation, QueryPage, Turn, TurnPage } from '../types';

export function AssistantPage({ enabled = conversationEnabled() }: { enabled?: boolean }) {
  return enabled ? <AssistantWorkspace /> : <><PageHeader title="AI 助手" description="基于当前可见资料的个人问答" />
    <section className="panel"><Alert type="info" title="会话功能暂未开放" description="你可以继续使用文档中心和知识搜索。" /></section></>;
}

export function AssistantWorkspace() {
  const { session } = useSession(), navigate = useNavigate(), { conversationId } = useParams();
  const query = useResource(signal => session.json<QueryPage<Conversation>>('/api/conversations?limit=20', { signal }, false), 'conversations');
  const [items, setItems] = useState<Conversation[]>([]), [cursor, setCursor] = useState<string | null>(null);
  const [error, setError] = useState(''), [busy, setBusy] = useState(false), [creating, setCreating] = useState(false);
  const binding = useRef<{ title?: string; requestId: string } | null>(null), lock = useRef(false);
  const [controller] = useState(() => new AbortController());
  useEffect(() => () => controller.abort(), [controller]);
  useEffect(() => { setItems(query.data?.items ?? []); setCursor(query.data?.nextCursor ?? null); }, [query.data]);
  return <><PageHeader title="AI 助手" description="私有会话 · 回答仅基于你的当前可见资料" />
    <div className="assistant-layout"><aside className="panel"><h2>我的会话</h2>
      <Button type="primary" block disabled={query.loading} onClick={() => setCreating(value => !value)}>新建会话</Button>
      {creating && <Form layout="vertical" onFinish={async ({ title }: { title?: string }) => {
        if (lock.current) return; lock.current = true; setBusy(true); setError('');
        const normalized = title?.trim() || undefined;
        if (!binding.current || binding.current.title !== normalized) binding.current = { title: normalized, requestId: crypto.randomUUID() };
        try {
          const result = await session.post<{ conversation: Conversation; replayed: boolean }>('/api/conversations', binding.current, false, controller.signal);
          if (controller.signal.aborted) return;
          binding.current = null; setCreating(false);
          setItems(previous => [...new Map([result.conversation, ...previous].map(item => [item.id, item])).values()]
            .sort((a, b) => b.createdAt.localeCompare(a.createdAt) || (BigInt(a.id) > BigInt(b.id) ? -1 : 1)));
          navigate(`/assistant/${result.conversation.id}`);
        } catch (failure) { if (!controller.signal.aborted && !isAbort(failure)) setError(messageOf(failure)); }
        finally { lock.current = false; setBusy(false); }
      }}><Form.Item name="title" label="标题（可选）" style={{ marginTop: 16 }}><Input maxLength={120} placeholder="新会话" /></Form.Item>
        <Button htmlType="submit" loading={busy} disabled={query.loading}>创建</Button></Form>}
      <div style={{ marginTop: 20 }}><Feedback loading={query.loading} error={query.error} retry={query.reload} />
        {items.map(item => <Link className={`conversation-item ${conversationId === item.id ? 'active' : ''}`} key={item.id} to={`/assistant/${item.id}`}>{item.title}</Link>)}
        {!query.loading && !query.error && !items.length && <p className="muted">创建你的第一个会话</p>}
        {cursor && <Button disabled={busy} onClick={async () => {
          if (lock.current) return; lock.current = true; setBusy(true); setError('');
          try { const result = await session.json<QueryPage<Conversation>>(`/api/conversations?limit=20&cursor=${encodeURIComponent(cursor)}`, { signal: controller.signal }, false);
            if (!controller.signal.aborted) { setItems(previous => [...new Map([...previous, ...result.items].map(item => [item.id, item])).values()]); setCursor(result.nextCursor); } }
          catch (failure) { if (!controller.signal.aborted && !isAbort(failure)) setError(messageOf(failure)); }
          finally { lock.current = false; setBusy(false); }
        }}>更多会话</Button>}
        {error && <Alert type="warning" title={error} />}</div>
    </aside><section className="panel">{conversationId ? <ConversationPanel key={conversationId} conversationId={conversationId}
      onDeleted={() => { setItems(previous => previous.filter(item => item.id !== conversationId)); navigate('/assistant', { replace: true }); }} />
      : <div style={{ padding: '48px 12px' }}><h2>从一个问题开始</h2><p className="muted">选择或新建会话，查阅历史并继续提问。</p></div>}</section></div>
  </>;
}

export function ConversationPanel({ conversationId, onDeleted }: { conversationId: string; onDeleted: () => void }) {
  const { state } = useSession();
  return state.phase === 'ready' ? <ConversationContent key={`${conversationId}:${state.epoch}`} conversationId={conversationId} onDeleted={onDeleted} /> : null;
}

function ConversationContent({ conversationId, onDeleted }: { conversationId: string; onDeleted: () => void }) {
  const { session } = useSession();
  const query = useResource(async signal => {
    const id = positiveId(conversationId);
    const [meta, history] = await Promise.all([session.json<Conversation>(`/api/conversations/${id}`, { signal }, false),
      session.json<TurnPage>(`/api/conversations/${id}/turns?limit=20`, { signal }, false)]);
    return { meta, history };
  }, conversationId);
  const [turns, setTurns] = useState<Turn[]>([]), [cursor, setCursor] = useState<bigint | null>(null), [question, setQuestion] = useState('');
  const [running, setRunning] = useState(false), [error, setError] = useState(''), [hint, setHint] = useState('');
  const [temporary, setTemporary] = useState<{ question: string; text: string; citations: Citation[] } | null>(null);
  const [citation, setCitation] = useState<Citation | null>(null), [confirmDelete, setConfirmDelete] = useState(false), [quarantined, setQuarantined] = useState(false);
  const [deleting, setDeleting] = useState(false), [paging, setPaging] = useState(false);
  const [liveMeta, setLiveMeta] = useState<Conversation | null>(null);
  const binding = useRef<TurnBinding | undefined>(undefined), lock = useRef(false), deleteLock = useRef(false), pageLock = useRef(false);
  const generation = useRef(0), stream = useRef<AbortController | null>(null), reconciliation = useRef<AbortController | null>(null);
  const [context] = useState(() => new AbortController());
  const dispose = () => { generation.current++; stream.current?.abort(); reconciliation.current?.abort(); };
  useEffect(() => () => { context.abort(); generation.current++; stream.current?.abort(); reconciliation.current?.abort(); }, [context]);
  useEffect(() => {
    const hidden = () => {
      if (document.visibilityState !== 'hidden') return;
      generation.current++; stream.current?.abort(); reconciliation.current?.abort();
      setQuarantined(true); setTemporary(null); setTurns([]); setCitation(null); setQuestion(''); setError(''); setHint('');
      binding.current = undefined; lock.current = false; setRunning(false);
    };
    document.addEventListener('visibilitychange', hidden);
    return () => document.removeEventListener('visibilitychange', hidden);
  }, []);
  useEffect(() => { setTurns(mergeTurns([], query.data?.history.items ?? [])); setCursor(query.data?.history.nextCursor ?? null); setLiveMeta(query.data?.meta ?? null); setCitation(null); }, [query.data]);
  const meta = liveMeta ?? query.data?.meta;

  const send = async (fresh: boolean) => {
    if (lock.current || !query.data || !meta || quarantined) return;
    if (meta.activeTurnId && binding.current?.question !== question) { setHint('当前有处理中轮次，请稍后刷新核对。'); return; }
    let request: TurnBinding;
    try {
      // 不确定提交的匹配重试保留首次序号；明确主动重发才创建新的请求标识。
      request = !fresh && binding.current?.question === question ? binding.current : bindTurn(question, meta.lastTurnSeq);
    } catch (failure) { setError(messageOf(failure)); return; }
    binding.current = request; lock.current = true; setRunning(true); setError(''); setHint(''); setCitation(null);
    const controller = new AbortController(); stream.current = controller;
    const currentGeneration = ++generation.current, epoch = session.getSnapshot().epoch;
    const active = () => !context.signal.aborted && generation.current === currentGeneration && session.getSnapshot().epoch === epoch && session.getSnapshot().phase === 'ready';
    let turnId: string | null = null;
    setTemporary({ question: request.question, text: '', citations: [] });
    try {
      const result = await openTurn(session, conversationId, request, controller.signal, id => {
        turnId = id; if (active()) setLiveMeta(previous => ({ ...(previous ?? meta), activeTurnId: id }));
      }, event => {
        if (!active()) return;
        if (event.type === 'delta') setTemporary(previous => previous && { ...previous, text: previous.text + event.text });
        if (event.type === 'citation') setTemporary(previous => previous && { ...previous, citations: [...previous.citations, event.citation] });
        if (event.type === 'error') setError(event.code === 'SEARCH_UNAVAILABLE' ? '检索暂不可用，请稍后重试。' : '生成或保存暂未完成，请查看历史状态。');
      });
      if (active() && result === 'eof') setHint('连接已结束，正在核对已保存内容。');
    } catch (failure) {
      if (active() && !isAbort(failure)) {
        setError(failure instanceof ApiError && failure.status === 503 ? '生成服务暂不可用，请稍后重试。' : messageOf(failure));
        if (failure instanceof ApiError && failure.status === 409) { binding.current = undefined; query.reload(); }
      }
    } finally {
      if (active() && turnId) {
        const controller = new AbortController(); reconciliation.current = controller;
        try {
          const finished = await reconcileTurn(() => session.json<Turn>(`/api/conversations/${conversationId}/turns/${turnId}`, { signal: controller.signal }, false), controller.signal,
            turn => { if (active()) {
              setTemporary(null); setTurns(previous => mergeTurns(previous, [turn]));
              setLiveMeta(previous => ({ ...(previous ?? meta), activeTurnId: turn.status === 'RUNNING' ? turn.id : null,
                lastTurnSeq: turn.turnSeq > (previous ?? meta).lastTurnSeq ? turn.turnSeq : (previous ?? meta).lastTurnSeq }));
            } });
          if (active()) {
            if (finished) {
              const updated = await session.json<Conversation>(`/api/conversations/${conversationId}`, { signal: controller.signal }, false);
              if (active()) { setLiveMeta(updated); binding.current = undefined; setQuestion(''); setHint(''); }
            }
            else setHint('持久状态仍在处理中，请稍后刷新核对。');
          }
        } catch (failure) { if (active() && !isAbort(failure)) { setTemporary(null); setTurns([]); setError(previous => previous || messageOf(failure)); setHint('请稍后刷新核对保存结果。'); } }
      } else if (active()) { setTemporary(null); setHint('尚未确认受理结果；相同输入可使用匹配重试。'); }
      if (active()) { setRunning(false); lock.current = false; }
    }
  };
  return <><div className="toolbar"><h2 style={{ flex: 1, margin: 0 }}>{query.data?.meta.title ?? '会话'}</h2>
    <Button disabled={running} onClick={() => { setQuarantined(false); query.reload(); }}>刷新历史</Button>
    <Button danger disabled={deleting || !query.data} onClick={() => setConfirmDelete(true)}>删除会话</Button></div>
    <Feedback loading={query.loading} error={query.error} retry={query.reload} />
    {!query.loading && !query.error && !quarantined && <>
      {turns.map(turn => <TurnCard key={turn.id} value={turn} onOpen={setCitation} />)}
      {cursor != null && <Button loading={paging} onClick={async () => {
        if (pageLock.current) return; pageLock.current = true; setPaging(true); setError(''); const gen = generation.current;
        try { const page = await session.json<TurnPage>(`/api/conversations/${conversationId}/turns?limit=20&cursor=${String(cursor)}`, { signal: context.signal }, false);
          if (!context.signal.aborted && gen === generation.current) { setTurns(previous => mergeTurns(previous, page.items)); setCursor(page.nextCursor); } }
        catch (failure) { if (!context.signal.aborted && gen === generation.current && !isAbort(failure)) setError(messageOf(failure)); }
        finally { pageLock.current = false; setPaging(false); }
      }}>更多轮次</Button>}
      {temporary && <article className="turn"><div className="question"><SafeText text={temporary.question} /></div><p className="muted">生成中 · 内容尚待保存确认</p>
        <SafeText text={temporary.text} /><References citations={temporary.citations} onOpen={setCitation} /></article>}
      <div className="composer"><Input.TextArea aria-label="问题" value={question} onChange={event => setQuestion(event.target.value)} disabled={running} autoSize={{ minRows: 3, maxRows: 10 }} placeholder="向知识库提问…" />
        <div className="toolbar" style={{ marginTop: 12 }}><Button type="primary" disabled={running || !question.trim() || (!!meta?.activeTurnId && binding.current?.question !== question)} onClick={() => void send(false)}>{binding.current ? '匹配重试' : '发送问题'}</Button>
          {binding.current && <Button disabled={running || !!meta?.activeTurnId} onClick={() => void send(true)}>主动重新发送</Button>}
          {running && <Button onClick={() => { stream.current?.abort(); setHint('已停止接收，正在核对保存结果。'); }}>停止</Button>}
          <span className="muted">最多 8000 个字符 · 请核对参考材料</span></div></div>
    </>}
    {quarantined && <Alert type="info" title="已关闭当前连接并清除本页内容。可刷新核对保存状态。" />}
    {error && <Alert type="warning" title={error} />}{hint && <p role="status" className="muted">{hint}</p>}
    <Modal title={`删除会话「${query.data?.meta.title ?? conversationId}」？`} open={confirmDelete} confirmLoading={deleting} okText="删除会话" okButtonProps={{ danger: true }} cancelText="取消" onCancel={() => { if (!deleting) setConfirmDelete(false); }} onOk={async () => {
      if (deleteLock.current) return; deleteLock.current = true; setDeleting(true); setError('');
      dispose(); setQuarantined(true); setTemporary(null); setTurns([]); setCitation(null); setQuestion(''); binding.current = undefined; setRunning(false); lock.current = false;
      try { await session.json(`/api/conversations/${positiveId(conversationId)}`, { method: 'DELETE', signal: context.signal }, false); if (!context.signal.aborted) onDeleted(); }
      catch (failure) { if (!context.signal.aborted && !isAbort(failure)) { if (failure instanceof ApiError && failure.status === 404) onDeleted(); else setError('删除结果未确认，请刷新核对；不会自动重复删除。'); } }
      finally { deleteLock.current = false; setDeleting(false); setConfirmDelete(false); }
    }}><p>将删除本人会话正文与历史。目标：{query.data?.meta.title ?? conversationId}。</p></Modal>
    <CitationDrawer citation={query.data && !quarantined ? citation : null} onClose={() => setCitation(null)} />
  </>;
}
