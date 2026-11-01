import { useEffect, useState } from 'react';
import { Alert, Button, Descriptions, Drawer, Input, Space, Table, Tag } from 'antd';
import { messageOf } from '../api';
import { Feedback, PageHeader } from '../components';
import { useSession } from '../SessionProvider';
import { useAdminAccess } from './access';
import { auditLocalTime, auditPath, auditValue, type AuditChange, type AuditFilters, type AuditMetadata } from './auditApi';
import { useAuditQuery, type AuditQuery } from './auditQuery';

type Draft = { range: string; from: string; to: string; limit: number; status: string; operationScope: string; operation: string;
  actorUserId: string; targetType: string; targetId: string; requestId: string; loginUsername: string };
const freshDraft = (): Draft => ({ range: 'recent', from: '', to: '', limit: 20, status: '', operationScope: '', operation: '', actorUserId: '', targetType: '', targetId: '', requestId: '', loginUsername: '' });
const scopeHints = ['USER_CREATE', 'USER_ENABLE', 'USER_DISABLE', 'USER_MOVE_DEPARTMENT', 'USER_CLEARANCE_CHANGE', 'USER_PASSWORD_CHANGE', 'USER_PASSWORD_RESET',
  'USER_ROLE_ASSIGN', 'USER_ROLE_REVOKE', 'ROLE_CREATE', 'ROLE_PERMISSION_GRANT', 'ROLE_PERMISSION_REVOKE', 'SPACE_ARCHIVE', 'DOCUMENT_RELOCATE',
  'DOCUMENT_SECURITY_LEVEL_CHANGE', 'AUTH_LOGIN_SUCCESS', 'AUTH_LOGIN_FAILURE', 'SSO_LOGIN', 'SSO_BIND', 'EXTERNAL_BIND', 'EXTERNAL_UNBIND', 'PARSE_DEAD_LETTER_REPLAY'];
const nullable = (value: string | null) => value === null ? '未记录' : value;
const displayTime = (value: string) => <time dateTime={value} title={value}>{new Date(value).toLocaleString('zh-CN', { timeZoneName: 'shortOffset' })}</time>;
const resultTag = (status: string) => <Tag color={status === 'SUCCESS' ? 'green' : status === 'FAILED' ? 'red' : 'default'}>{({ SUCCESS: '成功', FAILED: '失败' } as Record<string, string>)[status] ?? status}</Tag>;

export function AuditsPage() {
  const { state } = useSession(), access = useAdminAccess();
  if (state.phase !== 'ready' || access.status !== 'ready') return <Feedback loading={state.phase === 'loading' || access.status === 'loading'} error="当前身份或审计资格未确认。" />;
  if (!access.has('audit:read')) return <Alert type="info" title="当前账号没有审计读取资格。" />;
  return <AuditsContent key={`${state.epoch}|${access.permissionFactsKey ?? 'audit:read'}`} />;
}
function AuditsContent() {
  const query = useAuditQuery();
  const [draft, setDraft] = useState(freshDraft), [inputError, setInputError] = useState('');
  const shown = query.forbidden ? freshDraft() : draft;
  useEffect(() => { if (query.forbidden) { setDraft(freshDraft()); setInputError(''); } }, [query.forbidden]);
  const edit = (key: keyof Draft, value: string) => setDraft(previous => ({ ...previous, [key]: value }));
  function submit() {
    if (query.forbidden) return;
    try {
      const { range, from, to, ...values } = draft;
      const filters: AuditFilters = { ...values, ...(range === 'custom' ? { from: auditLocalTime(from), to: auditLocalTime(to) } : {}) };
      auditPath(filters); query.submit(filters); setInputError('');
    } catch (error) { setInputError(messageOf(error)); }
  }
  const textInput = (key: keyof Draft, label: string, list?: string) => <div className="audit-filter"><label htmlFor={`audit-${key}`}>{label}</label>
    <Input id={`audit-${key}`} value={String(shown[key])} disabled={query.forbidden} list={list} onChange={event => edit(key, event.target.value)} /></div>;
  return <><PageHeader title="审计查询" description="查询已记录的管理与登录事件，核对操作结果" extra={<Button disabled={query.forbidden || query.loading} onClick={query.refresh}>刷新列表</Button>} />
    <p className="muted">包含企业登录与绑定；不包含 AI 调用与独立向量重建记录。未查到记录不表示操作没有发生。</p>
    <section className="panel"><form aria-label="审计筛选" onSubmit={event => { event.preventDefault(); submit(); }}>
      <Space wrap align="end">
        <div className="audit-filter"><label htmlFor="audit-range">时间范围</label><select id="audit-range" className="audit-select" value={shown.range} disabled={query.forbidden} onChange={event => edit('range', event.target.value)}>
          <option value="recent">最近 7 天</option><option value="custom">自定义时间</option></select></div>
        {shown.range === 'custom' && <><div className="audit-filter"><label htmlFor="audit-from">起始时间（含）</label><Input id="audit-from" type="datetime-local" value={shown.from} disabled={query.forbidden} onChange={event => edit('from', event.target.value)} /></div>
          <div className="audit-filter"><label htmlFor="audit-to">截止时间（不含）</label><Input id="audit-to" type="datetime-local" value={shown.to} disabled={query.forbidden} onChange={event => edit('to', event.target.value)} /></div></>}
        <div className="audit-filter"><label htmlFor="audit-status">结果</label><select id="audit-status" className="audit-select" value={shown.status} disabled={query.forbidden} onChange={event => edit('status', event.target.value)}>
          <option value="">全部结果</option><option value="SUCCESS">成功</option><option value="FAILED">失败</option></select></div>
        {textInput('operationScope', '操作域', 'audit-scope-hints')}
      </Space>
      <datalist id="audit-scope-hints">{scopeHints.map(value => <option key={value} value={value} />)}</datalist>
      <details className="audit-advanced"><summary>高级筛选</summary><Space wrap align="end">
        {textInput('operation', '操作')}{textInput('actorUserId', '操作者 ID')}{textInput('targetType', '目标类型', 'audit-type-hints')}
        {textInput('targetId', '目标 ID')}{textInput('requestId', '请求标识')}{textInput('loginUsername', '本地登录用户名')}
      </Space><datalist id="audit-type-hints">{['user', 'auth', 'department', 'role', 'space', 'space-acl', 'doc-acl', 'document', 'external-identity', 'PARSE_DEAD_LETTER'].map(value => <option key={value} value={value} />)}</datalist>
        <p className="muted">条件按 AND 精确匹配；目标 ID 须同时提供目标类型。本地登录用户名仅匹配本地登录事件，不适用于企业 SSO。</p></details>
      <Space wrap><Button htmlType="submit" type="primary" disabled={query.forbidden}>查询</Button>
        <Button disabled={query.forbidden} onClick={() => { setDraft(freshDraft()); setInputError(''); query.reset(); }}>重置</Button>
        <label htmlFor="audit-limit">每次条数</label><select id="audit-limit" className="audit-select" value={shown.limit} disabled={query.forbidden} onChange={event => {
          const limit = Number(event.target.value); setDraft(previous => ({ ...previous, limit })); setInputError(''); query.submit({ ...query.filters, limit });
        }}>{[20, 50, 100].map(value => <option key={value} value={value}>{value} 条</option>)}</select></Space>
      <p className="muted">编辑条件后点击查询生效。时间按本地时区输入，最长 31 天；起点包含、终点不包含。页面进入后台后清理筛选，恢复时重新确认资格并查询最近 7 天。</p>
      {inputError && <Alert type="error" title={inputError} />}
    </form></section>
    {query.error && <Alert type={query.forbidden ? 'warning' : 'error'} showIcon title={query.error}
      action={<Button disabled={query.loading} onClick={query.retry}>{query.forbidden ? '重新读取审计' : query.status === 400 ? '重新查询' : '重试读取'}</Button>} style={{ marginBottom: 16 }} />}
    {query.loading && <Feedback loading />}
    {query.window && <section className="panel"><p>本次窗口：{displayTime(query.window.from)} 至 {displayTime(query.window.to)}（含起点，不含终点）</p>
      <p>已加载 {query.items.length} 条</p>
      <Table<AuditMetadata> rowKey="eventId" pagination={false} dataSource={query.items} scroll={{ x: 1100 }} columns={[
        { title: '时间', render: (_, row) => displayTime(row.createdAt) }, { title: '操作域', dataIndex: 'operationScope' },
        { title: '操作', dataIndex: 'operation' }, { title: '结果', render: (_, row) => resultTag(row.status) },
        { title: '操作者 ID', render: (_, row) => nullable(row.actorUserId) }, { title: '目标', render: (_, row) => `${row.targetType} · ${nullable(row.targetId)}` },
        { title: '详情', render: (_, row) => <Button size="small" onClick={() => query.open(row.eventId)}>查看事件 {row.eventId}</Button> },
      ]} locale={{ emptyText: '当前查询没有已记录事件。' }} />
      {query.hasMore && !query.error && <Button style={{ marginTop: 16 }} loading={query.loading} onClick={query.more}>继续读取</Button>}
    </section>}
    {query.detail && <AuditDrawer query={query} />}
  </>;
}
function AuditDrawer({ query }: { query: AuditQuery }) {
  const detail = query.detail!, data = detail.data, meta = data?.metadata;
  return <Drawer title={`审计事件 ${detail.eventId}`} open onClose={query.close}>
    <Feedback loading={detail.loading} />
    {detail.error && <Alert type="warning" title={detail.status === 404 ? '该事件不存在或当前不可访问。' : detail.error}
      action={detail.status !== 404 && <Button onClick={() => query.open(detail.eventId)}>重试详情</Button>} />}
    {data && meta && !detail.loading && !detail.error && <><Descriptions column={1} items={[
      { key: 'event', label: '事件编号', children: meta.eventId }, { key: 'id', label: '内部编号', children: meta.id },
      { key: 'time', label: '记录时间', children: displayTime(meta.createdAt) }, { key: 'scope', label: '操作域', children: meta.operationScope },
      { key: 'operation', label: '操作', children: meta.operation }, { key: 'status', label: '结果', children: resultTag(meta.status) },
      { key: 'actor', label: '操作者 ID', children: nullable(meta.actorUserId) }, { key: 'type', label: '目标类型', children: meta.targetType },
      { key: 'target', label: '目标 ID', children: nullable(meta.targetId) }, { key: 'request', label: '请求标识', children: meta.requestId },
      { key: 'username', label: '本地登录尝试用户名', children: nullable(meta.attemptedUsername) },
    ]} />
      {!data.summaryAvailable ? <Alert type="info" title="该事件的摘要不可用，以上为已记录的安全元数据。" /> : data.changes.length === 0 ? <p>无可展示变更。</p>
        : <Table<AuditChange & { key: number }> rowKey="key" pagination={false} size="small" dataSource={data.changes.map((change, key) => ({ ...change, key }))} columns={[
          { title: '字段', dataIndex: 'field' }, { title: '变更前', render: (_, change) => <span className="content-text">{auditValue(change.before, change.field)}</span> },
          { title: '变更后', render: (_, change) => <span className="content-text">{auditValue(change.after, change.field)}</span> },
        ]} />}
    </>}
  </Drawer>;
}
