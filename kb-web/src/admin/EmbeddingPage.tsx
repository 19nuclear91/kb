import { useEffect, useRef, useState } from 'react';
import { Alert, Button, Descriptions, Select, Space, Table, Tag } from 'antd';
import { Feedback, PageHeader } from '../components';
import { useSession } from '../SessionProvider';
import { perm, useAdminAccess } from './access';
import { useAdminResource } from './hooks';
import { OperationDialog } from './OperationDialog';
import { embeddingRun, embeddingStatus, exactText, operationWrites, profiles, workingStates,
  type EmbeddingProfile, type EmbeddingReceipt, type EmbeddingRun, type Exact } from './operationsApi';

const states: Record<string, string> = { UNINITIALIZED: '未初始化', ACTIVE: '已激活', QUIESCING: '维护准备', REBUILDING: '向量重算', VERIFYING: '后台校验中', BLOCKED: '作业阻塞', SUCCEEDED: '作业成功' };
const errors: Record<string, string> = { MODEL_UNAVAILABLE: '模型暂不可用', INVALID_RESPONSE: '模型响应不符合要求', LEASE_LOST: '执行资格已失效', CONTENT_CHANGED: '内容已变化', MISSING_CHUNKS: '当前版本缺少分块', COVERAGE_INVALID: '实际覆盖不完整', READINESS_EXPIRED: '就绪检查已过期', RETRY_EXHAUSTED: '自动重试已耗尽', DATABASE_ERROR: '数据访问异常', INTERNAL_ERROR: '内部故障' };
type Dialog = { kind: 'start'; epoch: Exact } | { kind: 'resume'; run: EmbeddingRun };

export function EmbeddingPage() {
  const { state } = useSession(), access = useAdminAccess();
  if (access.status !== 'ready' || state.phase !== 'ready') return <Feedback loading={access.status === 'loading'} error="当前运维资格未确认。" />;
  if (!access.has(perm.embeddingRead)) return <Alert type="info" title="当前账号没有向量管理读取资格。" />;
  return <EmbeddingContent key={`${state.epoch}|${access.factsKey ?? ''}|${access.has(perm.embeddingRebuild)}`} />;
}
function EmbeddingContent() {
  const { session } = useSession(), access = useAdminAccess();
  const [visible, setVisible] = useState(document.visibilityState !== 'hidden'), tracked = useRef<string | null>(null);
  const [dialog, setDialog] = useState<Dialog | null>(null), [receipt, setReceipt] = useState('');
  const catalog = useAdminResource(visible ? signal => profiles(session, signal) : null, 'embedding-profiles');
  const current = useAdminResource(visible ? async signal => {
    const status = await embeddingStatus(session, signal);
    const id = status.runId ?? tracked.current;
    const run = id ? await embeddingRun(session, id, signal) : null;
    if (!signal.aborted && status.runId) tracked.current = status.runId;
    return { status, run };
  } : null, 'embedding-current');
  const { reload } = current;
  useEffect(() => {
    const change = () => { const next = document.visibilityState !== 'hidden'; setVisible(next); if (next) access.check(true); };
    document.addEventListener('visibilitychange', change);
    return () => document.removeEventListener('visibilitychange', change);
  }, [access.check]);
  const status = current.data?.status, run = current.data?.run;
  useEffect(() => {
    if (!visible || current.loading || current.error || !status || !workingStates.includes(status.state)) return;
    const timer = setTimeout(reload, 5000);
    return () => clearTimeout(timer);
  }, [visible, current.loading, current.error, status, reload]);
  const refresh = () => { current.reload(); catalog.reload(); };
  const confirmed = visible && !!status && !current.loading && !current.error && !!catalog.data && !catalog.loading && !catalog.error;
  const writable = access.has(perm.embeddingRebuild), startable = confirmed && writable && !!status && ['ACTIVE', 'UNINITIALIZED'].includes(status.state) && !status.runId;
  const resumable = confirmed && writable && status?.state === 'BLOCKED' && run?.state === 'BLOCKED' && run.runId === status.runId
    && run.targetProfileId === status.targetProfileId && catalog.data?.some(row => row.ready && row.profile.profileId === run.targetProfileId);
  const finished = run?.state === 'SUCCEEDED' && status?.state === 'ACTIVE' && run.targetProfileId === status.activeProfileId;
  return <><PageHeader title="向量重建" description="查看模型与维护状态，发起重建或恢复阻塞作业" extra={<Button onClick={refresh} disabled={!visible || current.loading}>重新读取</Button>} />
    {!visible && <Alert type="info" title="页面读取已暂停，返回后重新确认身份与资格。" />}
    {receipt && <Alert type="success" title={receipt} style={{ marginBottom: 16 }} />}
    <Feedback loading={current.loading && !status} error={current.error} retry={refresh} />
    {status && <section className="panel">
      <Space><h2>系统状态</h2><Tag>{states[status.state] ?? '未知状态'}</Tag></Space>
      {status.state !== 'ACTIVE' && <Alert type="warning" title="维护期间搜索仅使用授权 BM25；上传仍可用，向量召回关闭，普通解析任务等待。" style={{ marginBottom: 16 }} />}
      <Descriptions column={2} items={[
        { key: 'active', label: '当前模型配置', children: status.activeProfileId ?? '尚未激活' },
        { key: 'target', label: '目标模型配置', children: status.targetProfileId ?? '无' },
        { key: 'epoch', label: '控制代次', children: exactText(status.epoch) },
        { key: 'local', label: '当前应用实例', children: status.localEmbedding?.ready ? '就绪' : '未就绪或不可确认' },
      ]} />
      <p className="muted">当前应用实例状态只代表本次响应所在实例。</p>
      {writable && <Space><Button disabled={!startable || !!dialog} onClick={() => setDialog({ kind: 'start', epoch: status.epoch })}>发起重建</Button>
        <Button disabled={!resumable || !!dialog} onClick={() => { if (run) setDialog({ kind: 'resume', run }); }}>恢复阻塞作业</Button></Space>}
    </section>}
    {run && <section className="panel"><h2>{states[run.state] ?? '作业状态待确认'}</h2>
      <Descriptions column={2} items={[
        { key: 'id', label: '作业标识', children: run.runId }, { key: 'target', label: '作业目标', children: run.targetProfileId },
        { key: 'total', label: '总分块数', children: exactText(run.totalCount) }, { key: 'success', label: '成功分块数', children: exactText(run.succeededCount) },
        { key: 'skipped', label: '跳过分块数', children: exactText(run.skippedCount) }, { key: 'retry', label: '下次尝试', children: run.nextAttemptAt ?? '无' },
      ]} />
      {run.errorCode && <Alert type="warning" title={errors[run.errorCode] ?? '作业故障，请核查受控运维记录。'} />}
      {run.state === 'VERIFYING' && <p>重算计数完成后仍需后台校验，校验通过才恢复向量召回。</p>}
      {finished && <Alert type="success" title="重建已完成，目标模型已激活。" />}
    </section>}
    <section className="panel"><h2>已登记模型配置</h2><Feedback loading={catalog.loading} error={catalog.error} retry={refresh} />
      {catalog.data && <Table<EmbeddingProfile> size="small" rowKey={row => row.profile.profileId} pagination={false} dataSource={catalog.data}
        columns={[{ title: '配置标识', render: (_, row) => row.profile.profileId }, { title: '模型', render: (_, row) => row.profile.model },
          { title: '维度', render: (_, row) => row.profile.dimension }, { title: '查询与文档联合就绪', render: (_, row) => row.ready ? '就绪' : '未就绪' }]} />}
    </section>
    {dialog && <EmbeddingDialog value={dialog} catalog={catalog.data ?? []} allowed={!!(dialog.kind === 'start'
      ? startable && status && exactText(status.epoch) === exactText(dialog.epoch)
      : resumable && run && exactText(run.version) === exactText(dialog.run.version))} refresh={refresh} close={() => setDialog(null)}
      done={value => { const received = value as EmbeddingReceipt; tracked.current = received.runId; setReceipt('请求已受理，作业将在后台执行。'); setDialog(null); refresh(); }} />}
  </>;
}
function EmbeddingDialog({ value, catalog, allowed, refresh, close, done }: { value: Dialog; catalog: EmbeddingProfile[]; allowed: boolean; refresh: () => void; close: () => void; done: (value: unknown) => void }) {
  const [target, setTarget] = useState(value.kind === 'resume' ? value.run.targetProfileId : catalog.find(row => row.ready)?.profile.profileId ?? '');
  const ready = catalog.some(row => row.ready && row.profile.profileId === target);
  return <OperationDialog title={value.kind === 'start' ? '确认发起重建' : '确认恢复作业'} scope={value.kind === 'start' ? 'embedding-start' : `embedding-resume:${value.run.runId}`}
    description={value.kind === 'start' ? '此操作影响全局检索：搜索保留授权 BM25，新解析任务等待。受理后不能取消或即时切回旧向量。' : `继续原作业 ${value.run.runId}，保留成功进度并沿原目标执行。`}
    max={500} allowed={allowed && ready} build={reason => value.kind === 'start' ? operationWrites.start(target, value.epoch, reason) : operationWrites.resume(value.run.runId, value.run.version, reason)}
    onDone={done} onClose={close} onRefresh={refresh}>
    {locked => value.kind === 'start' ? <div style={{ marginBottom: 16 }}><label htmlFor="embedding-target">目标模型配置</label>
      <Select id="embedding-target" value={target || undefined} disabled={locked} onChange={setTarget} style={{ display: 'block' }}
        options={catalog.map(row => ({ value: row.profile.profileId, label: `${row.profile.profileId} · ${row.ready ? '就绪' : '未就绪'}`, disabled: !row.ready }))} /></div>
      : <p>目标模型配置：{target}</p>}
  </OperationDialog>;
}
