import { useEffect, useRef, useState } from 'react';
import { Alert, Button, Empty, Space, Tag } from 'antd';
import { ApiError, isAbort, messageOf } from '../api';
import { Feedback, PageHeader } from '../components';
import { useSession } from '../SessionProvider';
import { useResource } from '../useResource';
import { useReviewScope } from '../reviews/lifecycle';
import { markRead, notificationLabels, notificationPage, type NotificationItem, type NotificationPage } from '../notifications/api';
import { NotificationTargetPanel } from '../notifications/TargetPanel';
import { useUnreadCount } from '../notifications/unread';

/** 隐藏、账号切换或强制改密时卸载工作区；列表、游标、回执与目标只留在本次身份内存中。 */
export function NotificationsPage() {
  const scope = useReviewScope();
  return <><PageHeader title="站内通知" description="发给本人的业务提醒 · 已读不代表已处理" />
    {scope.active ? <NotificationWorkspace key={scope.key} /> : <Alert type="info" title="请先完成登录与身份确认" />}</>;
}

function NotificationWorkspace() {
  const { session } = useSession();
  const [revision, setRevision] = useState(0);
  const query = useResource(signal => notificationPage(session, null, signal), `notifications/${revision}`);
  const unread = useUnreadCount();
  const reload = () => { setRevision(value => value + 1); unread.refresh(); };
  return <><Feedback loading={query.loading} error={query.error} retry={reload} />
    {query.data && <NotificationList key={revision} first={query.data} reload={reload} onRead={unread.refresh} />}</>;
}

type Receipt = 'sending' | 'uncertain' | 'failed';
const without = (value: Record<string, Receipt>, id: string) => Object.fromEntries(Object.entries(value).filter(([key]) => key !== id));

function NotificationList({ first, reload, onRead }: { first: NotificationPage; reload: () => void; onRead: () => void }) {
  const { session } = useSession();
  const [items, setItems] = useState(first.items), [cursor, setCursor] = useState(first.nextCursor);
  const [busy, setBusy] = useState(false), [error, setError] = useState(''), [notice, setNotice] = useState('');
  const [receipts, setReceipts] = useState<Record<string, Receipt>>({});
  const [opened, setOpened] = useState<NotificationItem | null>(null);
  const paging = useRef(false), sending = useRef(new Set<string>()), [controller] = useState(() => new AbortController());
  useEffect(() => () => controller.abort(), [controller]);

  const read = async (id: string) => {
    if (sending.current.has(id)) return;
    sending.current.add(id); setReceipts(value => ({ ...value, [id]: 'sending' })); setNotice('');
    try {
      const receipt = await markRead(session, id, controller.signal);
      if (controller.signal.aborted) return;
      setItems(value => value.map(item => item.notificationId === id ? { ...item, readAt: receipt.readAt } : item));
      setReceipts(value => without(value, id)); onRead();
    } catch (failure) {
      if (controller.signal.aborted || isAbort(failure)) return;
      if (failure instanceof ApiError && failure.status === 404) {
        setItems(value => value.filter(item => item.notificationId !== id));
        setOpened(value => value?.notificationId === id ? null : value);
        setReceipts(value => without(value, id)); setNotice('该通知已不存在或不属于当前账号。');
      } else setReceipts(value => ({ ...value, [id]: failure instanceof ApiError && failure.status === 0 ? 'uncertain' : 'failed' }));
    } finally { sending.current.delete(id); }
  };

  const more = async () => {
    if (paging.current || !cursor) return;
    paging.current = true; setBusy(true); setError('');
    try {
      const page = await notificationPage(session, cursor, controller.signal);
      if (controller.signal.aborted) return;
      setItems(value => [...new Map([...value, ...page.items].map(item => [item.notificationId, item])).values()]);
      setCursor(page.nextCursor);
    } catch (failure) { if (!controller.signal.aborted && !isAbort(failure)) setError(messageOf(failure)); }
    finally { paging.current = false; if (!controller.signal.aborted) setBusy(false); }
  };

  return <><section className="panel"><div className="toolbar"><Button onClick={reload}>刷新通知</Button>
    <span className="muted">每次读取按当前权限核对；不可见的通知不显示占位或数量</span></div>
    {notice && <Alert style={{ marginBottom: 12 }} type="info" title={notice} />}
    {items.length ? <ul className="notification-list">{items.map(item => {
      const state = receipts[item.notificationId];
      return <li key={item.notificationId}><Space wrap>
        <Tag color={item.readAt ? 'default' : 'blue'}>{item.readAt ? '已读' : '未读'}</Tag>
        <span>{notificationLabels[item.type]}</span>
        <span className="muted">{new Date(item.createdAt).toLocaleString('zh-CN')} · 文档 {item.target.documentId} · 版本 {item.target.versionId}</span>
        <Button onClick={() => setOpened(item)}>打开目标</Button>
        {!item.readAt && <Button loading={state === 'sending'} disabled={state === 'sending'} onClick={() => void read(item.notificationId)}>
          {state === 'uncertain' || state === 'failed' ? '重试标记已读' : '标记已读'}</Button>}
      </Space>
        {state === 'uncertain' && <Alert type="warning" title="已读结果待核对：可重试同一条标记，或刷新通知确认。这不表示审核已处理。" />}
        {state === 'failed' && <Alert type="error" title="标记已读未完成，请稍后重试。" />}
      </li>;
    })}</ul> : <Empty description="暂无可见通知" />}
    {error && <Alert style={{ marginTop: 16 }} type="warning" title={error} />}
    {cursor ? <Button style={{ marginTop: 16 }} loading={busy} disabled={busy} onClick={() => void more()}>更多通知</Button>
      : items.length > 0 && <p className="muted">已显示全部可见通知</p>}
  </section>
    {opened && <NotificationTargetPanel key={opened.notificationId} item={opened} onClose={() => setOpened(null)} />}</>;
}
