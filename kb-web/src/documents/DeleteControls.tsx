import { Alert, Button, Modal, Space } from 'antd';
import { errorMessage } from '../api';
import { canDelete } from './capabilities';
import type { DeletionState } from './deletion';
import type { OperationState } from './operations';

export function DeleteConfirmation({ state, operations, onCancel, onConfirm }: {
  state: DeletionState; operations: OperationState; onCancel: () => void; onConfirm: (acknowledge: boolean) => void;
}) {
  const open = state.phase === 'checking' || state.phase === 'confirming';
  const acknowledge = state.acknowledgement !== undefined;
  return <Modal title="删除整份文档" open={open} onCancel={onCancel} footer={null} destroyOnHidden>
    {state.phase === 'checking' && <p role="status">正在重新读取文档与删除资格…</p>}
    {state.facts && <p aria-label="删除目标">{state.facts.detail.title}</p>}
    <Alert type="warning" title="将删除整份文档及全部版本，取消未完成的处理和审核，当前无法恢复。"
      description="操作范围不受当前所选版本影响；不承诺原件立即物理擦除。" />
    {operations.pending > 0 && <p role="status">仍有文档操作等待结果，结束前不能提交删除。</p>}
    {operations.uncertain > 0 && <Alert type="warning" title="先前操作可能已经执行，结果仍未确认。"
      description="若仍要删除，请再次明确确认整份文档及全部版本的删除。" />}
    {state.uncertainResult && <p>先前删除结果仍未确认；当前可访问不表示原请求失败或回滚。</p>}
    {state.message && <Alert type="warning" title={state.message} />}
    <Space style={{ marginTop: 16 }}><Button onClick={onCancel}>取消</Button>
      <Button danger type="primary" disabled={state.phase !== 'confirming' || !canDelete(state.facts?.capabilities) || operations.pending > 0 || !operations.exclusive}
        onClick={() => onConfirm(acknowledge)}>{acknowledge ? '仍要删除整份文档' : '确认删除整份文档'}</Button>
    </Space>
  </Modal>;
}

export function DeletionFeedback({ state, onVerify, onReconfirm }: {
  state: DeletionState; onVerify: () => void; onReconfirm: () => void;
}) {
  if (state.phase === 'submitting') return <Alert type="info" title="删除请求已提交，正在等待结果。" description="离开页面不会撤销服务器已执行的操作。" />;
  if (state.phase === 'verifying') return <Alert type="info" title="正在只读核对当前文档和操作资格…" />;
  if (state.phase === 'confirmed') return <Alert type="success" title="文档已删除" />;
  if (!['uncertain', 'rejected', 'unavailable'].includes(state.phase)) return null;
  const allowed = canDelete(state.facts?.capabilities);
  const title = state.phase === 'unavailable' ? '文档当前不可访问。'
    : state.phase === 'rejected' ? state.rejection ? errorMessage(state.rejection) : state.message ?? '删除请求已被拒绝，请核对当前状态。'
      : state.facts ? allowed ? '当前仍可访问，原删除结果尚未确认。' : '当前可查看，但已无删除资格；原删除结果仍未确认。'
        : '删除结果尚未确认。';
  return <section className="panel" aria-label="删除结果"><Alert type={state.phase === 'rejected' ? 'error' : 'warning'} title={title}
    description={state.phase === 'rejected' ? state.uncertainResult ? '本次请求已明确拒绝；先前删除结果仍未确认。核对不会重发删除。'
      : '本次删除已被拒绝；核对只说明当前资源事实，不会重发删除。'
      : state.attempted ? '当前不可访问不能证明删除成功；当前可访问也不能证明原请求失败。核对不会重发删除。' : undefined} />
    {state.phase === 'rejected' && state.message && state.message !== title && <p role="status">{state.message}</p>}
    <Space style={{ marginTop: 16 }}><Button onClick={onVerify}>核对当前状态</Button>
      {allowed && <Button danger onClick={onReconfirm}>重新确认删除</Button>}
    </Space>
  </section>;
}
