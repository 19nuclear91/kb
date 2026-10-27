import { useId, useState, type ReactNode } from 'react';
import { Alert, Button, Input, Space } from 'antd';
import { messageOf } from '../api';
import type { WriteIntent } from './api';
import { useAdminWrite } from './hooks';
import { operationReason, operationsWrite } from './operationsApi';
import { AdminModal, WriteNotice } from './ui';

/** 一次确认绑定原目标与版本；后台重读只更新事实，不改写待核对意图。 */
export function OperationDialog({ title, scope, description, max, allowed, build, onDone, onClose, onRefresh, children }:
  { title: string; scope: string; description: ReactNode; max: number; allowed: boolean; build: (reason: string) => WriteIntent;
    onDone: (value: unknown) => void; onClose: () => void; onRefresh: () => void; children?: (locked: boolean) => ReactNode }) {
  const write = useAdminWrite(scope, operationsWrite), [reason, setReason] = useState(''), [error, setError] = useState(''), inputId = useId();
  const finish = async (retry = false) => {
    if (!retry && (!allowed || write.pending)) return;
    let intent: WriteIntent | undefined;
    if (!retry) {
      try { intent = build(operationReason(reason, max)); setError(''); }
      catch (failure) { setError(messageOf(failure)); return; }
    }
    const outcome = retry ? await write.retry() : await write.submit(intent!);
    if (outcome.status === 'confirmed') onDone(outcome.value);
    else if (outcome.status === 'rejected' && [404, 409].includes(outcome.error.status)) onRefresh();
  };
  return <AdminModal title={title} write={write} onClose={onClose}>
    <div className="muted" style={{ marginBottom: 16 }}>{description}</div>
    <WriteNotice write={write} onRetry={() => void finish(true)} onVerify={onRefresh} />
    {error && <Alert type="error" title={error} />}
    <form onSubmit={event => { event.preventDefault(); void finish(); }}>
      {children?.(write.pending)}
      <label htmlFor={inputId}>操作原因</label>
      <Input.TextArea id={inputId} value={reason} onChange={event => setReason(event.target.value)} disabled={write.pending}
        rows={3} placeholder="请说明操作原因，不要填写正文或凭据" style={{ marginBottom: 12 }} />
      <p className="muted">去除首尾空白后 1–{max} 个字符。</p>
      {!allowed && !write.pending && <Alert type="warning" title="当前事实或资格未确认，请重新读取后再确认操作。" />}
      <Space style={{ marginTop: 16 }}><Button type="primary" htmlType="submit" disabled={!allowed || write.pending}>确认提交</Button>
        <Button disabled={write.busy} onClick={onClose}>关闭</Button></Space>
    </form>
  </AdminModal>;
}
