import { useEffect, useRef, useState } from 'react';
import { Alert, Button, Descriptions, Drawer, Input, Select, Space, Table } from 'antd';
import { ApiError, messageOf } from '../api';
import { Feedback, PageHeader } from '../components';
import { useSession } from '../SessionProvider';
import { perm, useAdminAccess } from './access';
import { useAdminResource } from './hooks';
import { OperationDialog } from './OperationDialog';
import { deadLetterPage, deadLetterPath, deadLetterStates, exactText, failureClasses, operationWrites, type DeadLetter, type DeadLetterFilters } from './operationsApi';

export function ParseDeadLettersPage() {
  const { state } = useSession(), access = useAdminAccess();
  if (access.status !== 'ready' || state.phase !== 'ready') return <Feedback loading={access.status === 'loading'} error="当前运维资格未确认。" />;
  if (!access.has(perm.deadLetterRead)) return <Alert type="info" title="当前账号没有解析死信读取资格。" />;
  return <DeadLettersContent key={`${state.epoch}|${access.factsKey ?? ''}|${access.has(perm.deadLetterReplay)}`} />;
}
function DeadLettersContent() {
  const { session } = useSession(), access = useAdminAccess();
  const [filters, setFilters] = useState<DeadLetterFilters>({ status: 'OPEN', page: 1, size: 20 });
  const [draft, setDraft] = useState({ status: 'OPEN', failureClass: '', from: '', to: '' });
  const [error, setError] = useState(''), [selected, setSelected] = useState<string | null>(null), [replay, setReplay] = useState<DeadLetter | null>(null), [receipt, setReceipt] = useState('');
  const fallback = useRef(true);
  const list = useAdminResource(signal => deadLetterPage(session, filters, signal), deadLetterPath(filters));
  const page = list.data, row = page?.items.find(item => item.id === selected);
  useEffect(() => {
    if (list.loading || !page || page.items.length || page.page === 1 || !fallback.current) return;
    const total = BigInt(page.total), last = total === 0n ? 1n : (total + BigInt(page.size) - 1n) / BigInt(page.size);
    if (BigInt(page.page) <= last) return;
    fallback.current = false;
    setFilters(previous => ({ ...previous, page: Number(last > 2147483647n ? 2147483647n : last) }));
  }, [page, list.loading]);
  const change = (next: DeadLetterFilters) => { fallback.current = true; setSelected(null); setFilters(next); };
  const submitFilters = () => {
    try {
      const iso = (value: string) => { if (!value) return undefined; const date = new Date(value); if (!Number.isFinite(date.getTime())) throw new ApiError(400); return date.toISOString(); };
      const next = { ...filters, status: draft.status || undefined, failureClass: draft.failureClass || undefined, from: iso(draft.from), to: iso(draft.to), page: 1 };
      deadLetterPath(next); setError(''); change(next);
    } catch (failure) { setError(messageOf(failure)); }
  };
  const confirmed = !!page && !list.loading && !list.error;
  return <><PageHeader title="解析死信" description="筛选失败记录，查看安全摘要并按需单条重放" extra={<Button disabled={list.loading || !!replay} onClick={() => { fallback.current = true; list.reload(); }}>刷新列表</Button>} />
    {receipt && <Alert type="success" title={receipt} style={{ marginBottom: 16 }} />}
    <section className="panel"><form onSubmit={event => { event.preventDefault(); submitFilters(); }}><Space wrap align="end">
      <div><label htmlFor="dead-letter-status">状态</label><Select id="dead-letter-status" value={draft.status} disabled={!!replay} style={{ display: 'block', minWidth: 140 }}
        options={[{ value: '', label: '全部状态' }, ...deadLetterStates.map(value => ({ value, label: value }))]} onChange={status => setDraft(previous => ({ ...previous, status }))} /></div>
      <div><label htmlFor="dead-letter-class">失败分类</label><Select id="dead-letter-class" value={draft.failureClass} disabled={!!replay} style={{ display: 'block', minWidth: 140 }}
        options={[{ value: '', label: '全部分类' }, ...failureClasses.map(value => ({ value, label: value }))]} onChange={failureClass => setDraft(previous => ({ ...previous, failureClass }))} /></div>
      <div><label htmlFor="dead-letter-from">起始时间（含）</label><Input id="dead-letter-from" type="datetime-local" value={draft.from} disabled={!!replay} onChange={event => setDraft(previous => ({ ...previous, from: event.target.value }))} /></div>
      <div><label htmlFor="dead-letter-to">截止时间（不含）</label><Input id="dead-letter-to" type="datetime-local" value={draft.to} disabled={!!replay} onChange={event => setDraft(previous => ({ ...previous, to: event.target.value }))} /></div>
      <Button htmlType="submit" disabled={!!replay}>筛选</Button>
    </Space></form><p className="muted">时间按本地时区输入；列表手动刷新，每次读取保留查询审计。</p>{error && <Alert type="error" title={error} />}</section>
    <Feedback loading={list.loading} error={list.error} retry={list.reload} />
    {page && <section className="panel"><Table<DeadLetter> rowKey="id" size="small" pagination={false} dataSource={page.items} columns={[
      { title: '死信', dataIndex: 'id' }, { title: '任务', dataIndex: 'taskId' }, { title: '版本', dataIndex: 'versionId' },
      { title: '执行周期', render: (_, item) => exactText(item.executionGeneration) }, { title: '尝试次数', dataIndex: 'attempts' },
      { title: '失败分类', dataIndex: 'failureClass' }, { title: '错误代码', dataIndex: 'failureCode' }, { title: '状态', dataIndex: 'status' },
      { title: '操作', render: (_, item) => <Space><Button size="small" disabled={!!replay || !confirmed} onClick={() => setSelected(item.id)}>查看摘要 {item.id}</Button>
        {access.has(perm.deadLetterReplay) && <Button size="small" disabled={!!replay || !confirmed || item.status !== 'OPEN'} onClick={() => setReplay(item)}>重放 {item.id}</Button>}</Space> },
    ]} /><Space wrap style={{ marginTop: 16 }}><span>第 {filters.page} 页 · 共 {exactText(page.total)} 条</span>
      <Button disabled={!confirmed || !!replay || filters.page <= 1} onClick={() => change({ ...filters, page: filters.page - 1 })}>上一页</Button>
      <Button disabled={!confirmed || !!replay || filters.page === 2147483647 || BigInt(filters.page) * BigInt(filters.size) >= BigInt(page.total)} onClick={() => change({ ...filters, page: filters.page + 1 })}>下一页</Button>
      <Select aria-label="每页条数" value={filters.size} disabled={!!replay} options={[20, 50, 100].map(value => ({ value, label: `每页 ${value} 条` }))} onChange={size => change({ ...filters, page: 1, size })} />
    </Space></section>}
    {row && <Drawer title={`死信摘要 ${row.id}`} open onClose={() => setSelected(null)}><Descriptions column={1} items={[
      { key: 'task', label: '任务标识', children: row.taskId }, { key: 'version', label: '版本标识', children: row.versionId },
      { key: 'generation', label: '执行周期', children: exactText(row.executionGeneration) }, { key: 'attempts', label: '尝试次数', children: row.attempts },
      { key: 'failure', label: '失败分类', children: row.failureClass }, { key: 'code', label: '错误代码', children: row.failureCode },
      { key: 'summary', label: '脱敏摘要', children: <pre className="content-text">{row.errorSummary}</pre> }, { key: 'entered', label: '进入时间', children: row.enteredAt },
      { key: 'resolved', label: '处置时间', children: row.resolvedAt ?? '未处置' },
    ]} /></Drawer>}
    {replay && <OperationDialog title="确认重放死信" scope={`dead-letter:${replay.id}`} max={2000}
      description={<>死信 {replay.id} · 任务 {replay.taskId} · 版本 {replay.versionId}。重放会保留原失败记录并开启新周期；只表示重新入队，仍受 Embedding 维护等待约束。</>}
      allowed={confirmed && access.has(perm.deadLetterReplay) && !!page?.items.some(item => item.id === replay.id && item.status === 'OPEN')}
      build={reason => operationWrites.replay(replay.id, reason)} onClose={() => setReplay(null)} onRefresh={list.reload}
      onDone={() => { setReceipt(`死信 ${replay.id} 已重放，新执行周期已重新入队；解析结果需后续核对。`); setReplay(null); setSelected(null); fallback.current = true; list.reload(); }} />}
  </>;
}
