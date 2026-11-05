import { useEffect, useRef, useState } from 'react';
import { Alert, Button, Drawer, Space, Spin } from 'antd';
import { useSession } from './SessionProvider';
import { ApiError, filePath, isAbort, messageOf } from './api';
import { SafeText } from './components';
import type { Session } from './session';

export async function readFile(session: Session, path: string, signal: AbortSignal): Promise<Blob> {
  const response = await session.send(filePath(path), { signal });
  if (!response.ok) { await response.body?.cancel().catch(() => {}); throw new ApiError(response.status); }
  const type = (response.headers.get('Content-Type') ?? '').split(';')[0].trim().toLowerCase();
  if (!type || type === 'application/json' || type.endsWith('+json') || (path.endsWith('/preview') && !['application/pdf', 'text/plain', 'text/markdown'].includes(type))) {
    await response.body?.cancel().catch(() => {}); throw new ApiError(415);
  }
  const blob = await response.blob();
  if (signal.aborted) throw new DOMException('请求已停止', 'AbortError');
  return blob;
}

export function FilePreview({ path, onClose, onDenied, onUnsupported }: {
  path: string | null; onClose: () => void; onDenied?: () => void; onUnsupported?: () => void;
}) {
  const { session } = useSession();
  const denied = useRef(onDenied); denied.current = onDenied;
  const unsupported = useRef(onUnsupported); unsupported.current = onUnsupported;
  const [file, setFile] = useState<{ path: string; url?: string; text?: string; error?: string }>({ path: '' });
  useEffect(() => {
    if (!path) { setFile({ path: '' }); return; }
    const controller = new AbortController(); let url: string | undefined;
    setFile({ path });
    void readFile(session, path, controller.signal).then(async blob => {
      if (blob.type.startsWith('text/')) {
        const text = await blob.text(); if (!controller.signal.aborted) setFile({ path, text });
      } else if (!controller.signal.aborted) { url = URL.createObjectURL(blob); setFile({ path, url }); }
    }).catch(error => { if (!controller.signal.aborted && !isAbort(error)) {
      if (error instanceof ApiError && error.status === 415 && unsupported.current) { unsupported.current(); return; }
      setFile({ path, error: messageOf(error) });
      if (error instanceof ApiError && [401, 403, 404].includes(error.status)) denied.current?.();
    } });
    return () => { controller.abort(); if (url) URL.revokeObjectURL(url); };
  }, [path, session]);
  const current = file.path === path ? file : null;
  return <Drawer title="指定版本预览" open={!!path} onClose={onClose} size="large" destroyOnHidden>
    {current?.error ? <Alert type="warning" title={current.error} /> : current?.url ? <iframe className="preview-frame" title="PDF 版本预览" src={current.url} referrerPolicy="no-referrer" />
      : current?.text != null ? <SafeText text={current.text} /> : <Spin />}
  </Drawer>;
}

export function DownloadButton({ path, name, disabled, onDenied }: { path: string; name: string; disabled?: boolean; onDenied?: () => void }) {
  const { session } = useSession();
  const [error, setError] = useState(''), [busy, setBusy] = useState(false);
  const active = useRef<AbortController | null>(null);
  useEffect(() => {
    setError(''); setBusy(false);
    return () => { active.current?.abort(); active.current = null; };
  }, [path, session]);
  return <Space orientation="vertical"><Button disabled={disabled} loading={busy} onClick={async () => {
    if (disabled || active.current) return;
    const controller = new AbortController(); active.current = controller;
    setBusy(true); setError('');
    try {
      const blob = await readFile(session, path, controller.signal);
      const url = URL.createObjectURL(blob), link = document.createElement('a');
      try { link.href = url; link.download = name; link.rel = 'noopener'; link.click(); }
      finally { URL.revokeObjectURL(url); }
    } catch (failure) { if (active.current === controller && !controller.signal.aborted && !isAbort(failure)) { setError(messageOf(failure)); if (failure instanceof ApiError && [403, 404].includes(failure.status)) onDenied?.(); } }
    finally { if (active.current === controller) { active.current = null; if (!controller.signal.aborted) setBusy(false); } }
  }}>下载原件</Button>{error && <span role="alert" className="muted">{error}</span>}</Space>;
}
