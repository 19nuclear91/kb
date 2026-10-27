import { useEffect, useRef, useState } from 'react';
import { Alert, Button, Empty, Table, Tag } from 'antd';
import { Link } from 'react-router-dom';
import { useSession } from '../SessionProvider';
import { useResource } from '../useResource';
import { isAbort, messageOf } from '../api';
import { Feedback, PageHeader } from '../components';
import { favoritePage, type FavoriteItem, type FavoritePage } from '../favorites/api';
import { FavoriteButton } from '../favorites/FavoriteButton';
import { useFavorites } from '../favorites/useFavorites';

export function FavoritesPage() {
  const { state } = useSession();
  return <><PageHeader title="我的收藏" description="快速返回当前可查看的常用文档" />
    {state.phase === 'ready' ? <FavoriteWorkspace key={state.epoch} /> : <Alert type="info" title="请先完成登录与身份确认" />}
  </>;
}

function FavoriteWorkspace() {
  const { session } = useSession();
  const query = useResource(signal => favoritePage(session, null, signal), 'favorites');
  return <><Feedback {...query} retry={query.reload} />
    {query.data && <FavoriteList first={query.data} refresh={query.reload} />}</>;
}

function FavoriteList({ first, refresh }: { first: FavoritePage; refresh: () => void }) {
  const { session } = useSession();
  const [items, setItems] = useState(first.items), [cursor, setCursor] = useState(first.nextCursor);
  const [busy, setBusy] = useState(false), [error, setError] = useState('');
  const lock = useRef(false), [controller] = useState(() => new AbortController());
  useEffect(() => () => controller.abort(), [controller]);
  const favorites = useFavorites(items.map(item => item.documentId), first, true);
  const visible = items.filter(item => {
    const status = favorites.status(item.documentId);
    return status.phase !== 'unavailable' && !(status.phase === 'ready' && status.favorited === false);
  });
  return <section className="panel"><div className="toolbar"><Button onClick={refresh}>刷新收藏</Button>
    <span className="muted">按收藏顺序展示 · 每次读取重新核对文档权限</span></div>
    {visible.length ? <Table<FavoriteItem> rowKey="documentId" pagination={false} dataSource={visible} columns={[
      { title: '文档', render: (_, row) => <Link to={`/documents/${row.documentId}`}>{row.title}</Link> },
      { title: '当前版本', render: (_, row) => row.currentVersionNo == null ? '尚未发布' : `v${row.currentVersionNo}` },
      { title: '密级', render: (_, row) => <Tag>{`密级 ${row.securityLevel}`}</Tag> },
      { title: '收藏时间', render: (_, row) => new Date(row.favoritedAt).toLocaleString('zh-CN') },
      { title: '操作', render: (_, row) => <FavoriteButton id={row.documentId} favorites={favorites} /> },
    ]} /> : <Empty description="暂无可查看的收藏" />}
    {error && <Alert style={{ marginTop: 16 }} type="warning" title={error} />}
    {cursor && <Button style={{ marginTop: 16 }} aria-label="更多收藏" loading={busy} disabled={busy} onClick={async () => {
      if (lock.current) return; lock.current = true; setBusy(true); setError('');
      try {
        const page = await favoritePage(session, cursor, controller.signal);
        if (!controller.signal.aborted) {
          setItems(previous => [...new Map([...previous, ...page.items].map(item => [item.documentId, item])).values()]);
          setCursor(page.nextCursor);
        }
      } catch (failure) { if (!controller.signal.aborted && !isAbort(failure)) setError(messageOf(failure)); }
      finally { lock.current = false; if (!controller.signal.aborted) setBusy(false); }
    }}>更多收藏</Button>}
  </section>;
}
