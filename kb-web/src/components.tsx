import { Alert, Button, Empty, Space, Spin, Tag } from 'antd';
import type { ReactNode } from 'react';
import type { Citation, Turn } from './types';
import { citationPath, usageLabel, visibleTurn } from './logic';

export function PageHeader({ title, description, extra }: { title: string; description?: string; extra?: ReactNode }) {
  return <header className="page-header"><div><h1>{title}</h1><div className="muted">{description}</div></div>{extra}</header>;
}
export function Feedback({ loading, error, empty, retry }: { loading?: boolean; error?: string; empty?: boolean; retry?: () => void }) {
  if (loading) return <div className="panel" role="status"><Spin /> <span className="muted">正在读取…</span></div>;
  if (error) return <Alert type="error" showIcon title={error} action={retry && <Button onClick={retry}>重试</Button>} />;
  if (empty) return <Empty description="暂无可用内容" />;
  return null;
}
export function SafeText({ text }: { text: string }) { return <pre className="content-text">{text}</pre>; }
export function References({ citations, onOpen }: { citations: Citation[]; onOpen: (value: Citation) => void }) {
  if (!citations.length) return null;
  return <div style={{ marginTop: 18 }}><div className="muted" style={{ marginBottom: 8 }}>本轮参考材料 · 不代表逐句验证</div><Space wrap>
    {citations.map((citation, index) => <Button size="small" key={`${citation.chunkId}-${index}`} disabled={!citationPath(citation)} onClick={() => onOpen(citation)}>
      {citation.docTitle ?? citation.title ?? '参考材料'}{citation.versionNo != null ? ` · v${citation.versionNo}` : ''}{!citation.versionId ? ' · 版本未知' : ''}
    </Button>)}
  </Space></div>;
}
const statusNames: Record<string, string> = { RUNNING: '处理中', COMPLETED: '已完成', FAILED: '失败 · 未完成', CANCELLED: '已停止 · 未完成', INTERRUPTED: '已中断 · 未完成' };
export function TurnCard({ value, onOpen }: { value: Turn; onOpen: (value: Citation) => void }) {
  const turn = visibleTurn(value);
  return <article className="turn"><div className="question"><div className="muted">你</div><SafeText text={turn.question} /></div><div className="answer">
    <Space style={{ marginBottom: 12 }}><Tag color={turn.status === 'COMPLETED' ? 'green' : 'default'}>{statusNames[turn.status] ?? '状态待确认'}</Tag>
      {turn.status === 'COMPLETED' && turn.settlementState !== 'CONFIRMED' && turn.settlementState !== 'SETTLED' && <Tag>用量确认中</Tag>}</Space>
    {turn.visibility === 'BLOCKED' ? <Alert type="info" title="当前无法查看这条回答。" /> : <>
      <SafeText text={turn.answer ?? ''} /><References citations={turn.citations ?? []} onOpen={onOpen} />
    </>}
    <div className="muted" style={{ marginTop: 14 }}>{usageLabel(turn)}</div>
  </div></article>;
}
