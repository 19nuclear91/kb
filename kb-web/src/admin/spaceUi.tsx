import { useState, type ReactNode } from 'react';
import { Alert, Button } from 'antd';
import { positiveId, type ApiError } from '../api';
import { useSession } from '../SessionProvider';
import type { WriteIntent, adminWrite } from './api';
import { useAdminWrite, type WriteBindings } from './hooks';
import { AdminModal, WriteNotice, settle } from './ui';

export function validTarget(value: string): boolean { try { positiveId(value); return true; } catch { return false; } }
const notices = new WeakMap<object, { epoch: number; values: Map<string, string> }>();
/** 资源操作反馈在同一身份下的资格重核期间保留，身份切换立即清空。 */
export function useResourceNotice(target: string): [string, (text: string) => void] {
  const { session, state } = useSession();
  let record = notices.get(session);
  if (!record || record.epoch !== state.epoch) { record = { epoch: state.epoch, values: new Map() }; notices.set(session, record); }
  const store = record.values, key = `${state.epoch}:${target}`;
  const [view, setView] = useState({ key, text: store.get(target) ?? '' });
  return [view.key === key ? view.text : store.get(target) ?? '', text => { store.set(target, text); setView({ key, text }); }];
}
export function useResourceReceipt(target: string): [string, (text: string) => void] { return useResourceNotice(target); }
const resourceWrites = new WeakMap<object, { epoch: number; bindings: WriteBindings }>();
/** 未决请求保留到明确处理完成；弹窗关闭和资格重核不清空，身份切换清空。 */
export function useResourceWriteBindings(): WriteBindings {
  const { session, state } = useSession();
  let record = resourceWrites.get(session);
  if (!record || record.epoch !== state.epoch) {
    record?.bindings.clear();
    record = { epoch: state.epoch, bindings: new Map() };
    resourceWrites.set(session, record);
  }
  return record.bindings;
}
/** 弹窗只保存本次意图；发送、重试及身份隔离复用已有写入状态。 */
export function ResourceWriteDialog<T>({ title, scope, children, build, onDone, onRefresh, onClose, disabled = false, retryDisabled = disabled, execute, onVerify = onRefresh, onRejected, bindings }: {
  title: string; scope: string; children: ReactNode; build: (data: FormData) => WriteIntent;
  onDone: (result: T) => void; onRefresh: () => void; onClose: () => void; disabled?: boolean;
  execute?: typeof adminWrite;
  onVerify?: () => void;
  onRejected?: (error: ApiError) => void;
  retryDisabled?: boolean;
  bindings?: WriteBindings;
}) {
  const write = useAdminWrite(scope, execute, bindings);
  const receive = (outcome: Awaited<ReturnType<typeof write.submit<T>>>) => {
    if (outcome.status === 'rejected' && onRejected) onRejected(outcome.error);
    else settle(outcome, onDone, onRefresh);
  };
  return <AdminModal title={title} write={write} onClose={onClose}>
    <WriteNotice write={write} disabled={retryDisabled} onRetry={() => { if (!retryDisabled) void write.retry<T>().then(receive); }} onVerify={onVerify} />
    <form onSubmit={event => { event.preventDefault(); if (!disabled && !write.pending && !write.blocked) { const data = new FormData(event.currentTarget); void write.submit<T>(() => build(data)).then(receive); } }}>
      <fieldset disabled={write.busy}>{children}<div style={{ marginTop: 16 }}><Button type="primary" htmlType="submit" disabled={disabled || write.pending || write.blocked}>确认提交</Button></div></fieldset>
    </form>
  </AdminModal>;
}
export const fieldValue = (data: FormData, name: string) => String(data.get(name) ?? '');
export function ResourceStatus({ loading, error, retry }: { loading: boolean; error?: string; retry: () => void }) {
  return error ? <Alert type="warning" title={error} action={<Button onClick={retry}>重新读取</Button>} /> : loading ? <p role="status">正在读取…</p> : null;
}
export function TextField({ name, label, value = '', max, required = true, multiline = false }: { name: string; label: string; value?: string; max: number; required?: boolean; multiline?: boolean }) {
  return <label style={{ display: 'block', marginBottom: 12 }}>{label}{multiline
    ? <textarea className="ant-input" name={name} defaultValue={value} maxLength={max} required={required} />
    : <input className="ant-input" name={name} defaultValue={value} maxLength={max} required={required} />}</label>;
}
