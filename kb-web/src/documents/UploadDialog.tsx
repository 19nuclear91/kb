import { useEffect, useRef, useState } from 'react';
import { Alert, Button, Form, Input, Modal, Select } from 'antd';
import { useSession } from '../SessionProvider';
import { useDocumentOperations } from './OperationContext';
import { operationFailure } from './operations';
import { ApiError, isAbort, messageOf, positiveId } from '../api';
import type { QueryPage, UploadResult, UploadSpace } from '../types';

export function UploadDialog({ open, documentId, access, onDenied, onRecheck, onClose, onUploaded }: {
  open: boolean; documentId?: string; access?: { key: string; canUploadVersion: boolean; checking: boolean };
  onDenied?: () => void; onRecheck?: () => void; onClose: () => void; onUploaded: (result: UploadResult) => void;
}) {
  const { session } = useSession();
  const { operations, state: documentState } = useDocumentOperations();
  const [spaces, setSpaces] = useState<UploadSpace[]>([]), [cursor, setCursor] = useState<string | null>(null), [levels, setLevels] = useState<number[]>([]);
  const [busy, setBusy] = useState(false), [loading, setLoading] = useState(false), [error, setError] = useState('');
  const [file, setFile] = useState<File | null>(null), [form] = Form.useForm();
  const lock = useRef(false), active = useRef<AbortController | null>(null);
  const [deniedAt, setDeniedAt] = useState<string | null>(null);
  const rejectedKey = useRef<string | null>(null);
  const currentAccess = useRef(access); currentAccess.current = access;
  const paused = !!documentId && (!access?.canUploadVersion || access.checking || deniedAt === access.key);
  const disabled = documentState.exclusive || loading || (documentId ? paused : !spaces.length || !levels.length);
  useEffect(() => {
    if (!open) return;
    const controller = new AbortController(); active.current = controller;
    setError(''); setSpaces([]); setLevels([]); setCursor(null); setFile(null); form.resetFields();
    rejectedKey.current = null; setDeniedAt(null);
    if (!documentId) {
      setLoading(true);
      void Promise.all([session.json<QueryPage<UploadSpace>>('/api/auth/upload-spaces', { signal: controller.signal }),
        session.json<number[]>('/api/auth/security-levels', { signal: controller.signal })]).then(([page, dictionary]) => {
          if (!controller.signal.aborted) { setSpaces(page.items); setCursor(page.nextCursor); setLevels(dictionary); }
        }).catch(failure => { if (!controller.signal.aborted && !isAbort(failure)) setError(messageOf(failure)); })
        .finally(() => { if (!controller.signal.aborted) setLoading(false); });
    }
    return () => { controller.abort(); active.current = null; };
  }, [open, documentId, session, form]);
  return <Modal title={documentId ? '上传新版本' : '上传文档'} open={open} onCancel={() => { if (!busy) onClose(); }} footer={null} destroyOnHidden>
    {error && <Alert type="warning" title={error} style={{ marginBottom: 16 }} />}
    {paused && <Alert type="warning" title="上传资格尚未确认或已失效，提交已暂停。" style={{ marginBottom: 16 }}
      action={onRecheck && <Button disabled={access?.checking || busy || documentState.exclusive} onClick={onRecheck}>重新核对上传资格</Button>} />}
    <Form form={form} layout="vertical" onFinish={async (values: { spaceId: string; securityLevel: number; title?: string }) => {
      if (!open || lock.current || disabled || (documentId && rejectedKey.current !== null && rejectedKey.current === currentAccess.current?.key)) return;
      if (!file || file.size === 0 || file.size > 50 * 1024 * 1024) { setError('请选择非空且不超过 50 MiB 的文件；实际限制由服务器判定。'); return; }
      if (!/\.(pdf|txt|md|doc|docx|xls|xlsx|ppt|pptx)$/i.test(file.name)) { setError('只支持 PDF、TXT、Markdown（.md）和常规 Office 文档。'); return; }
      const request = active.current;
      if (!request || request.signal.aborted) return;
      const operation = operations?.beginWrite('upload-version');
      if (operations && !operation) return;
      lock.current = true; setBusy(true); setError('');
      try {
        const body = new FormData(); body.set('file', file);
        if (!documentId) { body.set('spaceId', positiveId(values.spaceId)); body.set('securityLevel', String(values.securityLevel)); if (values.title) body.set('title', values.title); }
        const result = await session.json<UploadResult>(documentId ? `/api/documents/${positiveId(documentId)}/versions` : '/api/documents', { method: 'POST', body, signal: request?.signal });
        operation?.settle('confirmed');
        if (request?.signal.aborted) return;
        onUploaded(result); onClose();
      } catch (failure) {
        operation?.settle(operationFailure(failure));
        if (!request.signal.aborted && active.current === request && !isAbort(failure)) {
          setError(failure instanceof ApiError && failure.status === 0 ? '提交结果尚未确认，请先核对文档列表，不会自动重复上传。' : messageOf(failure));
          if (documentId && failure instanceof ApiError && [403, 404].includes(failure.status)) {
            rejectedKey.current = currentAccess.current?.key ?? '';
            setDeniedAt(rejectedKey.current);
            onDenied?.();
          }
        }
      }
      finally { lock.current = false; setBusy(false); }
    }}>
      {!documentId && <><Form.Item name="spaceId" label="目标空间" rules={[{ required: true, message: '请选择可上传空间' }]}>
        <Select loading={loading} placeholder="选择有编辑资格的空间" options={spaces.map(space => ({ value: space.spaceId, label: space.name }))} /></Form.Item>
        {cursor && <Button size="small" disabled={loading} onClick={async () => {
          setLoading(true); setError('');
          try { const page = await session.json<QueryPage<UploadSpace>>(`/api/auth/upload-spaces?cursor=${encodeURIComponent(cursor)}`, { signal: active.current?.signal });
            if (!active.current?.signal.aborted) { setSpaces(previous => [...new Map([...previous, ...page.items].map(item => [item.spaceId, item])).values()]); setCursor(page.nextCursor); } }
          catch (failure) { if (!isAbort(failure)) setError(messageOf(failure)); } finally { setLoading(false); }
        }}>更多可上传空间</Button>}
        <Form.Item name="title" label="文档标题（可选）"><Input placeholder="缺省使用文件名" /></Form.Item>
        <Form.Item name="securityLevel" label="密级" rules={[{ required: true, message: '请选择密级' }]}><Select options={levels.map(value => ({ value, label: `密级 ${value}` }))} /></Form.Item>
      </>}
      <Form.Item label="原始文件" required><input aria-label="原始文件" type="file" accept=".pdf,.txt,.md,.doc,.docx,.xls,.xlsx,.ppt,.pptx" onChange={event => setFile(event.target.files?.[0] ?? null)} /></Form.Item>
      <p className="muted">支持 PDF、文本和常规 Office。上传成功后将异步解析。</p>
      <Button type="primary" htmlType="submit" loading={busy} disabled={disabled}>提交文件</Button>
    </Form>
  </Modal>;
}
