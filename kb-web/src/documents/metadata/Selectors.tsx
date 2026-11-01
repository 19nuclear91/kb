import { useEffect, useRef, useState, type ReactNode } from 'react';
import { Alert, Button } from 'antd';
import { useSession } from '../../SessionProvider';
import { isAbort, messageOf } from '../../api';
import { metadataApi, type CategoryContext, type CategoryItem, type CategoryPage, type Tag } from './api';

export function CategoryBrowser(props: { context: CategoryContext; onSelect: (category: CategoryItem | null) => void; selected?: string | null;
  onReady?: (page: CategoryPage | null) => void; actions?: (item: CategoryItem, page: CategoryPage) => ReactNode; nullLabel?: string; disabled?: boolean }) {
  return <CategoryBranch key={`${props.context.kind}:${props.context.id}`} {...props} />;
}
function CategoryBranch({ context, onSelect, selected, onReady, actions, nullLabel = '未分类', disabled = false }: Parameters<typeof CategoryBrowser>[0]) {
  const { session } = useSession();
  const [parent, setParent] = useState<CategoryItem | null>(null), [revision, setRevision] = useState(0);
  const [state, setState] = useState<{ key: string; page: CategoryPage | null; loading: boolean; error?: string }>({ key: '', page: null, loading: true });
  const controller = useRef<AbortController | null>(null), ready = useRef(onReady); ready.current = onReady;
  const key = `${context.kind}:${context.id}:${parent?.id ?? ''}:${revision}`;
  const current = state.key === key ? state : { key, page: null, loading: true };
  const load = (cursor: string | null, previous: CategoryPage | null) => {
    controller.current?.abort(); const request = new AbortController(); controller.current = request;
    ready.current?.(null); setState({ key, page: previous, loading: true });
    void metadataApi.categories(session, context, parent?.id ?? null, cursor, request.signal).then(page => {
      if (request.signal.aborted) return;
      const combined = { ...page, items: [...(previous?.items ?? []), ...page.items] };
      setState({ key, page: combined, loading: false }); ready.current?.(combined);
    }, error => { if (!request.signal.aborted && !isAbort(error)) { setState({ key, page: null, loading: false, error: messageOf(error) }); ready.current?.(null); } });
  };
  useEffect(() => { load(null, null); return () => { controller.current?.abort(); ready.current?.(null); }; }, [key, session]);
  const available = !disabled && !current.loading && !current.error && !!current.page;
  return <div className="metadata-categories"><div className="toolbar">
    <Button size="small" onClick={() => setParent(null)}>根目录</Button>
    {parent && <span>{parent.path.map(node => node.name).join(' / ')}</span>}
    <Button size="small" disabled={!available} onClick={() => onSelect(null)}>{nullLabel}</Button>
    <Button size="small" onClick={() => { setParent(null); setRevision(value => value + 1); }}>重新读取目录</Button>
  </div>
    {current.loading && <p role="status">正在读取分类…</p>}{current.error && <Alert type="warning" title={current.error} />}
    {available && !current.page?.items.length && <p>此层暂无分类</p>}
    <ul>{current.page?.items.map(item => <li key={item.id}>
      <Button aria-label={item.name} type={selected === item.id ? 'primary' : 'default'} size="small" disabled={!available} onClick={() => onSelect(item)}>{item.name}</Button>{' '}<span className="muted">排序 {item.sortOrder}</span>{' '}
      <Button size="small" disabled={!available} onClick={() => setParent(item)}>展开 {item.name}</Button>{actions?.(item, current.page!)}
    </li>)}</ul>
    {current.page?.hasMore && <Button disabled={current.loading} onClick={() => load(current.page!.nextCursor, current.page)}>继续读取分类</Button>}
  </div>;
}

export function TagPicker({ kind, documentId = null, value, onChange, disabled = false }: { kind: 'edit' | 'filter'; documentId?: string | null; value: Tag[]; onChange: (tags: Tag[]) => void; disabled?: boolean }) {
  const { session } = useSession();
  const [draft, setDraft] = useState(''), [search, setSearch] = useState(''), [revision, setRevision] = useState(0);
  const [state, setState] = useState<{ key: string; items: Tag[]; cursor: string | null; loading: boolean; error?: string }>({ key: '', items: [], cursor: null, loading: true });
  const active = useRef<AbortController | null>(null), key = `${kind}:${documentId}:${search}:${revision}`;
  const current = state.key === key ? state : { key, items: [], cursor: null, loading: true };
  const load = (cursor: string | null, items: Tag[]) => {
    active.current?.abort(); const request = new AbortController(); active.current = request;
    setState({ key, items, cursor, loading: true });
    void metadataApi.tags(session, kind, documentId, search, cursor, request.signal).then(page => {
      if (!request.signal.aborted) setState({ key, items: [...items, ...page.items], cursor: page.nextCursor, loading: false });
    }, error => { if (!request.signal.aborted && !isAbort(error)) setState({ key, items: [], cursor: null, loading: false, error: messageOf(error) }); });
  };
  useEffect(() => { load(null, []); return () => active.current?.abort(); }, [key, session]);
  return <div><p>{kind === 'filter' ? '多个标签全部匹配' : '最多选择 20 个已有标签；停用项可保留或移除'}</p>
    <ul>{value.map(tag => <li key={tag.id}>{tag.name}{!tag.enabled && '（停用）'} <Button size="small" disabled={disabled} onClick={() => onChange(value.filter(item => item.id !== tag.id))}>移除 {tag.name}</Button></li>)}</ul>
    <label>查找标签<input aria-label="查找标签" value={draft} onChange={event => setDraft(event.target.value)} /></label>
    <Button size="small" onClick={() => { setSearch(draft); setRevision(value => value + 1); }}>查询标签</Button>
    {current.loading && <p role="status">正在读取标签…</p>}{current.error && <Alert type="warning" title={current.error} />}
    <div>{current.items.filter(tag => !value.some(selected => selected.id === tag.id)).map(tag => <Button key={tag.id} size="small"
      disabled={disabled || current.loading || !!current.error || value.length >= 20 || (kind === 'edit' && !tag.enabled)} onClick={() => onChange([...value, tag])}>{tag.name}{!tag.enabled && '（停用）'}</Button>)}</div>
    {current.cursor && <Button size="small" disabled={current.loading} onClick={() => load(current.cursor, current.items)}>继续读取标签</Button>}
  </div>;
}
