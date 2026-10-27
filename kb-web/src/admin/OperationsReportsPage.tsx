import { useEffect, useState } from 'react';
import { Alert, Button, Space, Spin, Tag } from 'antd';
import { Link } from 'react-router-dom';
import { PageHeader } from '../components';
import { messageOf } from '../api';
import { perm, useAdminAccess } from './access';
import { operationsReportsPath, reportFormats, reportParsingStates, type OperationsCoverage, type OperationsReport } from './operationsReportsApi';
import { useOperationsReportsQuery } from './operationsReportsQuery';

const exact = (value: string | null) => value === null ? '—（历史未覆盖）' : value;
const coverageLabels = { COMPLETE: '完整覆盖', PARTIAL: '部分覆盖：仅为已观察部分', NOT_STARTED: '历史未覆盖' };
function Coverage({ value }: { value: OperationsCoverage }) {
  return <div><Tag>{coverageLabels[value.status]}</Tag><p>采集起点：{value.coverageStartedAt ?? '尚未开始采集'}</p>
    <p>有效观测区间：{value.effectiveFrom && value.effectiveTo ? `[${value.effectiveFrom}, ${value.effectiveTo})` : '无'}</p>
    {value.status !== 'COMPLETE' && <p className="muted">未覆盖历史保持未知，不能解释为零或业务下滑。</p>}</div>;
}
function Metric({ label, value }: { label: string; value: string | null }) { return <div><dt>{label}</dt><dd>{exact(value)}</dd></div>; }
function Report({ data }: { data: OperationsReport }) {
  return <>
    <div className="panel"><p>口径：{data.definitionVersion} · 日界：{data.dayTimeZone}</p><p>当前快照 asOf：{data.asOf}</p>
      <p>事件窗口（UTC，左闭右开）：[{data.from}, {data.to})</p></div>
    <section className="panel" aria-label="当前库存"><h2>当前库存</h2><p>以下为 asOf 当前库存，不是所选窗口内新增量或历史库存。包含归档内容，排除已删除文档。</p>
      <dl><Metric label="文档数" value={data.inventory.documentCount} /><Metric label="归档文档数（并集）" value={data.inventory.archivedDocumentCount} />
        <Metric label="全部版本数" value={data.inventory.versionCount} /></dl>
      <h3>版本格式分布</h3><p className="muted">原件声明扩展名，不代表真实 MIME 或格式质量；候选及失败版本均计入。</p>
      <dl>{reportFormats.map(key => <Metric key={key} label={key} value={data.inventory.formats[key]} />)}</dl>
      <h3>版本解析状态分布</h3><p className="muted">解析状态不表示审核或发布结果。</p>
      <dl>{reportParsingStates.map(key => <Metric key={key} label={key} value={data.inventory.parsingStates[key]} />)}</dl>
    </section>
    <section className="panel" aria-label="审核"><h2>审核</h2><dl><Metric label="当前可处理待审轮次" value={data.review.pendingReviewCount} /></dl>
      <p>待审量是当前库存，排除已删除、归档文档和归档空间，与事件窗口无关。通过／驳回按决定轮次计数，同版本重送独立计算。</p>
      <Coverage value={data.review.coverage} /><dl><Metric label="窗口通过轮次" value={data.review.approvedCount} /><Metric label="窗口驳回轮次" value={data.review.rejectedCount} />
        <Metric label="有效时长样本数" value={data.review.durationSampleCount} /><Metric label="未知时长样本数" value={data.review.durationUnknownCount} />
        <Metric label="处理总耗时（毫秒）" value={data.review.decisionElapsedMsTotal} /></dl>
      <p>平均处理耗时（毫秒）：{data.review.meanDecisionMs ?? (data.review.durationSampleCount === '0' ? '无有效样本' : '未知')}</p>
      <p className="muted">从送审到决定的自然耗时，包含等待和非工作时段。逐样本向下取整至毫秒，平均值保留两位；未知样本不当作零耗时。</p>
    </section>
    <section className="panel" aria-label="评论"><h2>评论创建</h2><Coverage value={data.comments.coverage} /><dl><Metric label="窗口根评论创建量" value={data.comments.rootCommentCount} />
      <Metric label="窗口回复创建量" value={data.comments.replyCount} /></dl><p>按历史创建事实计数，不是当前剩余正文数；删除不冲减，重试与通知不增量，私有审核意见不计入。</p></section>
    <section className="panel" aria-label="UTC 日趋势"><h2>UTC 日趋势</h2><p>首尾部分日按窗口截取。空缺表示历史未覆盖，部分覆盖值只代表已观察部分；不推算历史库存。</p>
      <div style={{ overflowX: 'auto' }}><table><thead><tr><th>UTC 日及区间</th><th>审核覆盖</th><th>通过轮次</th><th>驳回轮次</th><th>评论覆盖</th><th>根评论创建</th><th>回复创建</th></tr></thead>
        <tbody>{data.days.map(day => <tr key={day.day}><td>{day.day}{day.partialDay && <Tag>部分日</Tag>}<small style={{ display: 'block' }}>[{day.from}, {day.to})</small></td>
          <td>{coverageLabels[day.review.coverage.status]}<small style={{ display: 'block' }}>{day.review.coverage.effectiveFrom ?? '无'} → {day.review.coverage.effectiveTo ?? '无'}</small></td>
          <td>{exact(day.review.approvedCount)}</td><td>{exact(day.review.rejectedCount)}</td><td>{coverageLabels[day.comments.coverage.status]}
            <small style={{ display: 'block' }}>{day.comments.coverage.effectiveFrom ?? '无'} → {day.comments.coverage.effectiveTo ?? '无'}</small></td>
          <td>{exact(day.comments.rootCommentCount)}</td><td>{exact(day.comments.replyCount)}</td></tr>)}</tbody></table></div></section>
  </>;
}
export function OperationsReportsPage() {
  const access = useAdminAccess(), query = useOperationsReportsQuery();
  const [draft, setDraft] = useState({ from: '', to: '' }), [error, setError] = useState('');
  useEffect(() => { setDraft({ from: '', to: '' }); setError(''); }, [query.resetKey]);
  if (access.status !== 'ready' || !access.has(perm.operationsReportsRead)) return null;
  return <><PageHeader title="运营概览" description="全局固定汇总 · 当前库存与历史事件分开展示" extra={access.has(perm.aiUsageRead) ? <Link to="/admin/ai/usage">查看 AI 用量</Link> : undefined} />
    <form className="panel" aria-label="报表时间窗口" onSubmit={event => {
      event.preventDefault();
      try {
        const filters = draft.from || draft.to ? { from: draft.from, to: draft.to } : {};
        operationsReportsPath(filters); query.submit(filters); setError('');
      } catch (cause) { setError(messageOf(cause)); }
    }}><p>默认最近 7 天；可显式提交最长 31 天的带偏移 ISO 时间。筛选只影响事件，不改变当前库存。条件仅保存在当前页面内存。</p>
      <Space wrap><label>开始时间（带偏移 ISO）<input aria-label="开始时间" value={draft.from} placeholder="2026-10-20T00:00:00Z" onChange={event => setDraft({ ...draft, from: event.target.value })} /></label>
        <label>结束时间（带偏移 ISO）<input aria-label="结束时间" value={draft.to} placeholder="2026-10-27T00:00:00Z" onChange={event => setDraft({ ...draft, to: event.target.value })} /></label>
        <Button htmlType="submit" aria-label="查询">查询</Button><Button onClick={() => { setDraft({ from: '', to: '' }); setError(''); query.reset(); }}>重置为最近 7 天</Button>
        <Button disabled={query.loading || query.forbidden} onClick={query.refresh}>刷新已提交条件</Button></Space>
      {error && <Alert type="error" title={error} />}
    </form>
    {query.loading ? <div role="status" className="panel"><Spin /> 正在读取运营概览…</div> : query.error ? <Alert type="warning" title={query.error}
      description="当前没有可展示的成功结果。可显式重试；历史未知不会补零。" action={<Button onClick={query.retry}>重试读取</Button>} /> : query.data ? <Report data={query.data} /> : null}
  </>;
}
