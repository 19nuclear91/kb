import { useEffect, useState } from 'react';
import { Alert, Button, Descriptions, Drawer, Input, Space, Table, Tag } from 'antd';
import { messageOf } from '../api';
import { Feedback, PageHeader } from '../components';
import { useSession } from '../SessionProvider';
import { useAdminAccess } from './access';
import { auditLocalTime } from './auditApi';
import { aiDefaults, aiEnums, aiPath, metricLabels, type AiEvent, type AiFilters, type AiGroup, type AiKind, type AiMetadata, type Metrics } from './aiUsageApi';
import { useAiUsageQuery, type AiQuery } from './aiUsageQuery';

const titles = { usage: 'AI 用量', audits: 'AI 调用审计' };
const labels = { userId: '用户 ID', departmentId: '部门 ID', mode: '调用模式', generationStatus: '生成状态', usageStatus: '真实用量状态',
  settlementStatus: '结算状态', callId: '调用 ID', eventType: '事件类型' };
const time = (value: string) => <time dateTime={value} title={value}>{value}</time>;
const number = (value: string | null) => value === null ? '无已知样本' : value;
const usageLabels: Record<string, string> = { KNOWN: '已知', UNKNOWN: '未知', NOT_INVOKED: '未调用模型' };
type Draft = AiFilters & { range: string; localFrom: string; localTo: string };
const fresh = (): Draft => ({ ...aiDefaults(), range: 'recent', localFrom: '', localTo: '' });

export function AiUsagePage() { return <AiPage kind="usage" />; }
export function AiAuditsPage() { return <AiPage kind="audits" />; }
function AiPage({ kind }: { kind: AiKind }) {
  const { state } = useSession(), access = useAdminAccess(), code = kind === 'usage' ? 'ai:usage:read' : 'ai:audit:read';
  if (state.phase !== 'ready' || access.status !== 'ready') return <Feedback loading={state.phase === 'loading' || access.status === 'loading'} error="当前身份或 AI 读取资格未确认。" />;
  if (!access.has(code)) return <Alert type="info" title="当前账号没有此 AI 页面的读取资格。" />;
  return <AiContent key={`${state.epoch}|${access.permissionFactsKey ?? code}|${kind}`} kind={kind} />;
}
function Coverage({ meta }: { meta: AiMetadata }) {
  return <section className="panel" aria-label="采集覆盖">
    {!meta.collectionEnabled && <Alert type="info" title={meta.coverageStartedAt === null ? '尚未开始采集 AI 用量，历史不能按零调用解释。' : '当前已停止新调用采集，保留记录和既有收尾仍可查询。'} />}
    {meta.historyMayBeIncomplete && <Alert type="warning" title="此时间范围的历史可能不完整：可能早于覆盖起点、包含停采间隙，或普通历史已按保留期清理；异常保留记录不代表完整历史。" />}
    <p>本次窗口：{time(meta.from)} 至 {time(meta.to)}（含起点，不含终点）</p>
    <p>读取时刻 asOf：{time(meta.asOf)} · 按 UTC 日分组 · 当前保留 {meta.retentionDays} 天</p>
    <p>首次覆盖：{meta.coverageStartedAt === null ? '尚无覆盖记录' : time(meta.coverageStartedAt)}</p>
    <details><summary>查看采集区间（{meta.coverageIntervals.length}）</summary>
      {meta.coverageIntervals.map((interval, index) => <p key={index}>{time(interval.from)} 至 {time(interval.to)}</p>)}
      {meta.coverageIntervalsTruncated && <p>覆盖区间较多，当前提示未包含全部区间。</p>}
    </details>
  </section>;
}
function AiContent({ kind }: { kind: AiKind }) {
  const query = useAiUsageQuery(kind), [draft, setDraft] = useState<Draft>(fresh), [error, setError] = useState('');
  const shown = query.forbidden ? fresh() : draft;
  useEffect(() => { if (query.forbidden) { setDraft(fresh()); setError(''); } }, [query.forbidden]);
  const edit = (key: keyof Draft, value: string | number) => setDraft(previous => ({ ...previous, [key]: value }));
  function submit() {
    if (query.forbidden) return;
    try {
      const { range, localFrom, localTo, ...values } = draft;
      const filters = { ...values, ...(range === 'custom' ? { from: auditLocalTime(localFrom), to: auditLocalTime(localTo) } : {}) };
      aiPath(kind === 'usage' ? 'groups' : 'audits', filters); query.submit(filters); setError('');
    } catch (failure) { setError(messageOf(failure)); }
  }
  return <><PageHeader title={titles[kind]} description={kind === 'usage' ? '分别核对实际 token 与额度结算' : '按调用关联已记录的安全事件'}
    extra={<Button disabled={query.forbidden || query.loading} onClick={query.refresh}>刷新查询</Button>} />
    <section className="panel"><form aria-label="AI 查询筛选" onSubmit={event => { event.preventDefault(); submit(); }}>
      <Space wrap align="end"><div className="audit-filter"><label htmlFor="ai-range">时间范围</label><select id="ai-range" className="audit-select" disabled={query.forbidden} value={shown.range} onChange={event => edit('range', event.target.value)}>
        <option value="recent">最近 7 天</option><option value="custom">自定义时间</option></select></div>
        {shown.range === 'custom' && <><div className="audit-filter"><label htmlFor="ai-from">起始时间（含）</label><Input id="ai-from" type="datetime-local" value={shown.localFrom} disabled={query.forbidden} onChange={event => edit('localFrom', event.target.value)} /></div>
          <div className="audit-filter"><label htmlFor="ai-to">截止时间（不含）</label><Input id="ai-to" type="datetime-local" value={shown.localTo} disabled={query.forbidden} onChange={event => edit('localTo', event.target.value)} /></div></>}
        {(['userId', 'departmentId', ...(kind === 'audits' ? ['callId'] : [])] as ('userId' | 'departmentId' | 'callId')[]).map(key => <div className="audit-filter" key={key}>
          <label htmlFor={`ai-${key}`}>{labels[key]}</label><Input id={`ai-${key}`} value={shown[key] ?? ''} disabled={query.forbidden} onChange={event => edit(key, event.target.value)} /></div>)}
        {(Object.keys(aiEnums) as (keyof typeof aiEnums)[]).filter(key => kind === 'audits' || key !== 'eventType').map(key => <div className="audit-filter" key={key}>
          <label htmlFor={`ai-${key}`}>{labels[key]}</label><select id={`ai-${key}`} className="audit-select" value={shown[key] ?? ''} disabled={query.forbidden} onChange={event => edit(key, event.target.value)}>
            <option value="">全部</option>{aiEnums[key].map(value => <option key={value} value={value}>{value}</option>)}</select></div>)}
        {kind === 'usage' && <div className="audit-filter"><label htmlFor="ai-group">分组方式</label><select id="ai-group" className="audit-select" value={shown.groupBy} disabled={query.forbidden} onChange={event => edit('groupBy', event.target.value)}>
          <option value="day">UTC 日</option><option value="department">部门 ID</option><option value="user">用户 ID</option></select></div>}
        <div className="audit-filter"><label htmlFor="ai-limit">每次条数</label><select id="ai-limit" className="audit-select" value={shown.limit} disabled={query.forbidden} onChange={event => edit('limit', Number(event.target.value))}>
          {[20, 50, 100].map(value => <option key={value} value={value}>{value} 条</option>)}</select></div>
      </Space>
      <p className="muted">条件按 AND 精确匹配。编辑后点击查询生效；时间按设备本地时区输入，最长 31 天，返回时以带时区时间显示。进入后台或离开页面后清理，恢复时按最近 7 天重新读取。</p>
      <Space><Button htmlType="submit" type="primary" disabled={query.forbidden}>查询</Button><Button disabled={query.forbidden} onClick={() => { setDraft(fresh()); setError(''); query.reset(); }}>重置</Button></Space>
      {error && <Alert type="error" title={error} />}
    </form></section>
    {query.error && <Alert type={query.forbidden ? 'warning' : 'error'} title={query.error} action={<Button disabled={query.loading} onClick={query.retry}>{query.forbidden ? '重新读取 AI 数据' : query.status === 400 ? '重新查询' : '重试读取'}</Button>} />}
    {query.loading && <Feedback loading />}
    {query.metadata && <Coverage meta={query.metadata} />}
    {kind === 'usage' && query.overview && <section className="panel" aria-label="用量总览"><h2>用量总览</h2>
      <p>总览 asOf：{time(query.overview.asOf)}</p><Descriptions column={{ xs: 1, md: 3 }} items={(Object.keys(metricLabels) as (keyof Metrics)[]).map(key => ({ key, label: metricLabels[key], children: number(query.overview!.metrics[key]) }))} />
      <p>真实用量：KNOWN 为可靠累计统计，UNKNOWN 的 token 为空，NOT_INVOKED 为未调用模型。额度沿用各入口原结算规则，计划扣除不等于确认扣除，也不是金额。</p>
    </section>}
    {query.metadata && <section className="panel"><p>已加载 {query.items.length} {kind === 'usage' ? '组' : '条事件'}</p>
      {kind === 'usage' ? <GroupTable items={query.items as AiGroup[]} groupBy={query.filters.groupBy} /> : <EventTable query={query} />}
      {query.hasMore && !query.error && <Button loading={query.loading} onClick={query.more} style={{ marginTop: 16 }}>继续读取</Button>}
    </section>}
    {query.detail && <EventDrawer query={query} />}
  </>;
}
function GroupTable({ items, groupBy }: { items: AiGroup[]; groupBy: string }) {
  const keys: (keyof Metrics)[] = ['callCount', 'modelDispatchCount', 'knownCount', 'unknownCount', 'notInvokedCount', 'promptTokens', 'completionTokens', 'plannedChargedTokens', 'settledChargedTokens', 'pendingCount', 'reviewCount'];
  return <><p>每页分组有独立 asOf，活跃调用可能变化；跨页不代表同一冻结快照。</p><Table<AiGroup> rowKey="key" pagination={false} dataSource={items} scroll={{ x: 1700 }} locale={{ emptyText: '当前窗口没有已记录分组，需结合覆盖信息判断。' }} columns={[
    { title: groupBy === 'day' ? 'UTC 日期' : groupBy === 'department' ? '部门 ID' : '用户 ID', dataIndex: 'key', fixed: 'left' },
    ...keys.map(key => ({ title: metricLabels[key], render: (_: unknown, row: AiGroup) => number(row.metrics[key]) })),
    { title: '该页 asOf', render: (_, row) => time(row.asOf) },
  ]} /></>;
}
function EventTable({ query }: { query: AiQuery }) {
  const tokens = (row: AiEvent, field: string) => row.snapshot[field] ?? (row.snapshot.usageStatus === 'UNKNOWN' ? '未知（null）' : '未记录');
  return <Table<AiEvent> rowKey="eventId" pagination={false} dataSource={query.items as AiEvent[]} scroll={{ x: 2400 }} locale={{ emptyText: '当前窗口没有已记录 AI 事件，需结合覆盖信息判断。' }} columns={[
    { title: '发生时间', render: (_, row) => time(row.occurredAt) }, { title: '调用 ID', dataIndex: 'callId' }, { title: '事件类型', dataIndex: 'eventType' },
    { title: '模式', render: (_, row) => row.snapshot.mode ?? '未记录' }, { title: '用户 ID', render: (_, row) => row.snapshot.userId ?? '未记录' },
    { title: '部门 ID', render: (_, row) => row.snapshot.departmentId ?? '未记录' }, { title: '生成状态', render: (_, row) => row.snapshot.generationStatus ?? '未记录' },
    { title: '结果码', render: (_, row) => row.snapshot.resultCode ?? '未记录' },
    { title: '真实用量', render: (_, row) => row.snapshot.usageStatus ? <Tag>{usageLabels[row.snapshot.usageStatus] ? `${usageLabels[row.snapshot.usageStatus]} · ` : ''}{row.snapshot.usageStatus}</Tag> : '未记录' },
    { title: '实际输入 token', render: (_, row) => tokens(row, 'promptTokens') },
    { title: '实际输出 token', render: (_, row) => tokens(row, 'completionTokens') },
    { title: '计划扣除额度', render: (_, row) => row.snapshot.plannedChargedTokens ?? '未记录' },
    { title: '已确认结算额度', render: (_, row) => row.snapshot.settledChargedTokens ?? '未记录' },
    { title: '结算状态', render: (_, row) => row.snapshot.settlementStatus ?? '未记录' },
    { title: '详情', render: (_, row) => <Button size="small" onClick={() => query.open(row.eventId)}>查看事件 {row.eventId}</Button> },
  ]} />;
}
const snapshotLabels: Record<string, string> = { callId: '调用 ID', turnId: '轮次 ID', userId: '用户 ID', departmentId: '部门 ID', mode: '调用模式',
  generationStatus: '生成状态', resultCode: '结果码', usageStatus: '真实用量状态', settlementStatus: '结算状态', settlementPolicy: '结算策略',
  promptTokens: '实际输入 token', completionTokens: '实际输出 token', reservedTokens: '预留额度', plannedChargedTokens: '计划扣除额度', settledChargedTokens: '已确认结算额度',
  startedAt: '登记时间', finalizedAt: '生成终结时间', settledAt: '结算确认时间', elapsedMs: '耗时（毫秒）' };
function EventDrawer({ query }: { query: AiQuery }) {
  const detail = query.detail!, data = detail.data;
  return <Drawer title={`AI 事件 ${detail.eventId}`} open onClose={query.close}><Feedback loading={detail.loading} />
    {detail.error && <Alert type="warning" title={detail.status === 404 ? '该事件不存在或已清理。' : detail.error}
      action={detail.status !== 404 && <Button onClick={() => query.open(detail.eventId)}>重试详情</Button>} />}
    {data && !detail.loading && !detail.error && <><Descriptions column={1} items={[
      { key: 'event', label: '事件 ID', children: data.eventId }, { key: 'id', label: '内部编号', children: data.id },
      { key: 'call', label: '调用 ID', children: data.callId }, { key: 'kind', label: '事件类型', children: data.eventType },
      { key: 'occurred', label: '发生时间', children: time(data.occurredAt) }, { key: 'recorded', label: '入库时间', children: time(data.recordedAt) },
    ]} />{!data.snapshotAvailable ? <Alert type="info" title="此事件快照不可用，以上为安全关联信息。" /> : <Descriptions column={1} items={Object.entries(snapshotLabels).map(([key, label]) => ({ key, label,
      children: <span className="content-text">{data.snapshot[key] === null || data.snapshot[key] === undefined ? (['promptTokens', 'completionTokens'].includes(key) && data.snapshot.usageStatus === 'UNKNOWN' ? '未知（null）' : '未记录') : data.snapshot[key]}</span> }))} />}
      <p className="muted">这是该阶段提交时的快照。同一调用可有多个事件；详情不补查名称或正文。</p>
    </>}
  </Drawer>;
}
