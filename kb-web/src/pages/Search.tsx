import { useRef, useState, useEffect } from 'react';
import { Button, Form, Input, Tag, Empty } from 'antd';
import { useSession } from '../SessionProvider';
import { Feedback, PageHeader, SafeText } from '../components';
import { CitationDrawer } from '../CitationDrawer';
import { citationPath } from '../logic';
import { isAbort } from '../api';
import { searchErrorMessage, searchInputError, searchMaxQueryCodepoints } from '../search';
import { searchApi, searchBody } from '../search/api';
import { SearchFilterFields, draftFilters, emptyFilterDraft, filterSummary } from '../search/FilterFields';
import { useFavorites } from '../favorites/useFavorites';
import { FavoriteButton } from '../favorites/FavoriteButton';
import type { Citation, Hit } from '../types';

export function SearchPage({ maxQueryCodepoints = searchMaxQueryCodepoints }: { maxQueryCodepoints?: number }) {
  const { state } = useSession();
  const [visible, setVisible] = useState(document.visibilityState !== 'hidden');
  useEffect(() => {
    const change = () => setVisible(document.visibilityState !== 'hidden');
    document.addEventListener('visibilitychange', change);
    return () => document.removeEventListener('visibilitychange', change);
  }, []);
  return visible && state.phase === 'ready' ? <SearchWorkspace key={state.epoch} maximum={maxQueryCodepoints} /> : null;
}

function SearchWorkspace({ maximum }: { maximum: number }) {
  const { session } = useSession();
  const [form] = Form.useForm();
  const [hits, setHits] = useState<Hit[] | null>(null), [error, setError] = useState(''), [loading, setLoading] = useState(false);
  const [citation, setCitation] = useState<Citation | null>(null);
  const [draft, setDraft] = useState(emptyFilterDraft), [submitted, setSubmitted] = useState<{ query: string; summary: string } | null>(null);
  const request = useRef<AbortController | null>(null);
  const favorites = useFavorites((hits ?? []).flatMap(hit => { const id = hit.docId ?? hit.documentId; return id ? [id] : []; }), hits);
  const visible = hits?.filter(hit => !favorites.unavailable(hit.docId ?? hit.documentId ?? ''));
  const currentCitation = citation && !favorites.unavailable(citation.docId ?? citation.documentId ?? '') ? citation : null;
  useEffect(() => { if (citation && !currentCitation) setCitation(null); }, [citation, currentCitation]);
  useEffect(() => () => request.current?.abort(), []);
  return <><PageHeader title="知识搜索" description="在当前可检索的已发布资料中组合筛选，按相关性查看片段" />
    <section className="panel"><Form form={form} onFinishFailed={() => { request.current?.abort(); setHits(null); setError(''); setLoading(false); setCitation(null); setSubmitted(null); }} onFinish={async ({ query }: { query: string }) => {
      if (document.visibilityState === 'hidden' || searchInputError(query, maximum)) return;
      let filters;
      try { filters = draftFilters(draft); searchBody(query, filters); }
      catch (failure) { request.current?.abort(); setHits(null); setCitation(null); setSubmitted(null); setLoading(false); setError(searchErrorMessage(failure)); return; }
      request.current?.abort(); const controller = new AbortController(); request.current = controller;
      const epoch = session.getSnapshot().epoch;
      const active = () => !controller.signal.aborted && request.current === controller && session.getSnapshot().epoch === epoch && session.getSnapshot().phase === 'ready';
      setLoading(true); setHits(null); setError(''); setCitation(null);
      setSubmitted({ query, summary: filterSummary(draft) });
      try { const result = await searchApi.search(session, query, filters, controller.signal); if (active()) setHits(result); }
      catch (failure) { if (active() && !isAbort(failure)) setError(searchErrorMessage(failure)); } finally { if (active()) setLoading(false); }
    }}><div className="toolbar"><Form.Item name="query" style={{ flex: 1, margin: 0 }} rules={[{ validator: (_, value: string) => { const error = searchInputError(value, maximum); return error ? Promise.reject(new Error(error)) : Promise.resolve(); } }]}><Input size="large" placeholder="输入关键词或描述你要查找的内容" aria-label="搜索词" /></Form.Item><Button type="primary" htmlType="submit" size="large" loading={loading}>搜索</Button></div>
      <SearchFilterFields value={draft} onChange={setDraft} /></Form>
      <div className="muted">搜索词最多 {maximum} 个字符 · 每次最多显示 20 条相关片段 · 搜索内容仅在本页使用</div>
    </section><Feedback loading={loading} error={error} />
    {submitted && <section className="panel" aria-label="已提交条件"><h2>已提交条件</h2><SafeText text={submitted.query} /><p>{submitted.summary}</p></section>}
    {visible && <section className="panel">{!visible.length ? <Empty description="当前条件下未找到可用结果" /> : <><h2>相关片段 <span className="muted">{visible.length} 条</span></h2>{visible.map((hit, index) => <article key={`${hit.chunkId}/${index}`} className="search-hit">
      <h2><Button type="link" disabled={!citationPath(hit)} onClick={() => setCitation(hit)}>{hit.docTitle ?? '参考材料'}</Button></h2>
      <div style={{ marginBottom: 12 }}><Tag>{hit.versionNo == null ? '版本未知' : `v${hit.versionNo}`}</Tag><span className="muted">{hit.pageNo == null ? '页码未知' : `第 ${hit.pageNo} 页`}</span></div>
      <SafeText text={hit.content} />
      {(hit.docId ?? hit.documentId) && <FavoriteButton id={(hit.docId ?? hit.documentId)!} favorites={favorites} />}
    </article>)}</>}</section>}
    <CitationDrawer citation={currentCitation} onClose={() => setCitation(null)} />
  </>;
}
