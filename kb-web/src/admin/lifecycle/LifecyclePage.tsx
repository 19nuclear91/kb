import { useState } from 'react';
import { Alert, Button, Descriptions, Drawer, Select, Space, Table } from 'antd';
import { Feedback, PageHeader } from '../../components';
import { useSession } from '../../SessionProvider';
import { perm, useAdminAccess } from '../access';
import { useAdminResource, useCursorList } from '../hooks';
import { Note } from '../ui';
import { emergencyUsers, eventStatuses, lifecycleEvents, lifecycleItems, lifecycleStatus, lifecycleUser, lifecycleWrites } from './api';
import { EmergencyDialog, LifecycleActionDialog } from './dialogs';
import { LifecycleScope } from './scope';
import type { EmergencyUser, LifecycleEvent, LifecycleItem } from './types';

const labels: Record<string, string> = { UNMAPPED: '未匹配', REVIEW: '待核对', FAILED: '处理失败', OFFBOARDED: '已停权', CONFLICT: '事件冲突', IGNORED: '已忽略' };
export function LifecyclePage() {
  return <LifecycleScope target="lifecycle-page"><Content /></LifecycleScope>;
}
function Content() {
  const { session } = useSession();
  const status = useAdminResource(signal => lifecycleStatus(session, signal), 'lifecycle-status');
  const data = status.data;
  return <><PageHeader title="员工生命周期" description="钉钉离职处置、人工核对与独立应急保障" extra={<Button onClick={status.reload} disabled={status.loading}>刷新接入状态</Button>} />
    <Feedback loading={status.loading} error={status.error} retry={status.reload} />
    {data && <section className="panel"><Descriptions column={2} items={[
      { key: 'subscription', label: '订阅', children: data.subscriptionEnabled ? '已启用' : '已关闭；已有访问限制仍生效' },
      { key: 'connection', label: 'Stream 连接', children: data.connected ? '已连接' : '未连接' },
      { key: 'ready', label: '应急保障', children: data.emergencyReady ? '当前就绪' : '未就绪' },
      { key: 'managed', label: '全局受管账号', children: String(data.counts.managed) },
      { key: 'restricted', label: '全局受限账号', children: String(data.counts.restricted) },
      { key: 'unmapped', label: '全局未匹配项', children: String(data.counts.unmapped) },
      { key: 'review', label: '全局待核对项', children: String(data.counts.review) },
      { key: 'failed', label: '全局失败项', children: String(data.counts.failed) },
    ]} /><Note>全局摘要与下方游标列表独立读取。未匹配、待核对及失败项均不代表成功停权。</Note></section>}
    <Events onChanged={status.reload} /><Emergency onChanged={status.reload} />
  </>;
}

function Events({ onChanged }: { onChanged: () => void }) {
  const { session } = useSession(), access = useAdminAccess();
  const [status, setStatus] = useState('UNMAPPED'), [selected, setSelected] = useState<string | null>(null), [retry, setRetry] = useState<string | null>(null), [receipt, setReceipt] = useState('');
  const list = useCursorList((cursor, signal) => lifecycleEvents(session, cursor, status, signal), `lifecycle-events:${status}`);
  const refresh = () => { list.reload(); onChanged(); };
  return <section className="panel"><h2>事件处置</h2>
    <Space><Select aria-label="事件处置状态" value={status} disabled={!!retry} style={{ minWidth: 160 }} options={[{ value: '', label: '全部状态' }, ...eventStatuses.map(value => ({ value, label: labels[value] }))]}
      onChange={value => { setSelected(null); setStatus(value); }} /><Button disabled={!!retry || list.loading} onClick={refresh}>刷新事件</Button></Space>
    {receipt && <Alert type="success" title={receipt} />}<Feedback loading={list.loading} error={list.error} retry={list.reload} />
    <Table<LifecycleEvent> rowKey="id" pagination={false} size="small" dataSource={list.items} columns={[
      { title: '记录', dataIndex: 'id' }, { title: '记录时间', dataIndex: 'recordedAt' },
      { title: '已停权项', render: (_, row) => String(row.restricted) }, { title: '未匹配项', render: (_, row) => String(row.unmapped) },
      { title: '待核对项', render: (_, row) => String(row.review) }, { title: '失败项', render: (_, row) => String(row.failed) },
      { title: '冲突', render: (_, row) => row.conflicted ? '需核对' : '无' },
      { title: '操作', render: (_, row) => <Space><Button size="small" disabled={!!retry} onClick={() => setSelected(row.id)}>查看员工项 {row.id}</Button>
        {access.has(perm.lifecycleManage) && <Button size="small" disabled={!!retry || list.loading || !!list.error} onClick={() => setRetry(row.id)}>重试原事件 {row.id}</Button>}</Space> },
    ]} />
    <Note>当前已读取 {list.items.length} 条记录，非全局总数；每次最多读取 20 条。未匹配需先核对准确员工映射，重试不会建号或自动复职。</Note>
    {list.hasMore && <Button loading={list.loading} disabled={!!retry} onClick={list.more}>更多事件</Button>}
    {selected && <Drawer title={`事件 ${selected} 的员工处置项`} open onClose={() => setSelected(null)} size={720}><Items key={selected} eventId={selected} /></Drawer>}
    {retry && <LifecycleActionDialog title="重试原事件" scope={`lifecycle:retry:${retry}`} description={`只处理记录 ${retry} 的服务端原始事实；不会替换载荷或解除已有离职限制。`}
      build={reason => lifecycleWrites.retry(retry, reason)} result="原事件已重试，请读取逐员工结果；未匹配及待核对不表示成功停权。"
      onClose={() => setRetry(null)} onRefresh={refresh} onDone={message => { setReceipt(message); setRetry(null); setSelected(null); refresh(); }} />}
  </section>;
}
function Items({ eventId }: { eventId: string }) {
  const { session } = useSession();
  const list = useCursorList((cursor, signal) => lifecycleItems(session, eventId, cursor, signal), `lifecycle-items:${eventId}`);
  return <><Feedback loading={list.loading} error={list.error} retry={list.reload} />
    <Table<LifecycleItem> rowKey="id" pagination={false} size="small" dataSource={list.items} columns={[
      { title: '项编号', dataIndex: 'id' }, { title: '内部账号', render: (_, row) => row.userId ?? '尚未匹配' },
      { title: '处置状态', render: (_, row) => labels[row.status] }, { title: '尝试次数', dataIndex: 'attempts' },
      { title: '核对情况', render: (_, row) => row.resolvedAt ? '已核对处理' : '以当前状态为准' }, { title: '更新时间', dataIndex: 'updatedAt' },
    ]} /><Note>不展示原始员工标识或事件载荷。已停权记录是历史处置，当前账号状态需在账号详情核对。</Note>
    {list.hasMore && <Button loading={list.loading} onClick={list.more}>更多员工项</Button>}</>;
}
function Emergency({ onChanged }: { onChanged: () => void }) {
  const { session, state } = useSession(), access = useAdminAccess(), self = state.me!.user.userId;
  const list = useCursorList((cursor, signal) => emergencyUsers(session, cursor, signal), 'lifecycle-emergency');
  const canManage = access.has(perm.emergencyManage);
  const own = useAdminResource(canManage ? signal => lifecycleUser(session, self, signal) : null, `lifecycle-self:${self}`);
  const [dialog, setDialog] = useState<{ replacement: EmergencyUser | null } | null>(null), [receipt, setReceipt] = useState('');
  const candidate = own.data && own.data.kind !== 'MANAGED' && own.data.restriction === 'CLEAR' && own.data.enabled;
  const refresh = () => { list.reload(); own.reload(); onChanged(); };
  return <section className="panel"><h2>独立应急保障</h2><Note>只允许候选本人本地验证后登记或先建后换。保障资格由服务端按当前账号、部门、凭据和权限事实判断。</Note>
    {receipt && <Alert type="success" title={receipt} />}<Feedback loading={list.loading || own.loading} error={list.error || own.error} retry={refresh} />
    <Table<EmergencyUser> rowKey="userId" pagination={false} size="small" dataSource={list.items} columns={[
      { title: '账号', dataIndex: 'userId' }, { title: '显示名', dataIndex: 'displayName' }, { title: '当前保障资格', render: (_, row) => row.qualified ? '合格' : '不合格，需处理' },
      { title: '操作', render: (_, row) => canManage && row.userId !== self && <Button disabled={!candidate || own.loading || !!own.error || !!dialog || list.loading || !!list.error} onClick={() => setDialog({ replacement: row })}>由本人替换 {row.displayName}</Button> },
    ]} />
    <Space>{list.hasMore && <Button loading={list.loading} disabled={!!dialog} onClick={list.more}>更多保障账号</Button>}<Button disabled={!!dialog} onClick={refresh}>刷新应急保障</Button>
      {canManage && <Button disabled={!candidate || own.loading || !!own.error || !!dialog || own.data?.kind === 'EMERGENCY'} onClick={() => setDialog({ replacement: null })}>登记本人</Button>}</Space>
    {canManage && own.data && !candidate && <Note>本人当前状态不符合候选条件，不能登记或轮换。</Note>}
    {dialog && own.data && <EmergencyDialog user={own.data} replacement={dialog.replacement} onClose={() => setDialog(null)} onRefresh={refresh}
      onDone={message => { setReceipt(message); setDialog(null); refresh(); }} />}
  </section>;
}
