import { useEffect, useRef, useState } from 'react';
import { Alert, Button, Modal, Spin } from 'antd';
import { ApiError, isAbort } from '../api';
import { FilePreview } from '../FileAccess';
import { useSession } from '../SessionProvider';
import { closeOffice, heartbeat, launchOffice, officeError, previewInfo, type OfficeLaunch } from './api';

interface Props { path: string | null; onClose: () => void; onDenied?: () => void; fileName?: string }
export function VersionPreview(props: Props) {
  const { state } = useSession();
  const callbacks = useRef(props); callbacks.current = props;
  const [hidden, setHidden] = useState(false);
  const epoch = useRef(state.epoch);
  const changed = epoch.current !== state.epoch;
  useEffect(() => {
    if (epoch.current !== state.epoch) { epoch.current = state.epoch; setHidden(true); callbacks.current.onClose(); }
  }, [state.epoch]);
  useEffect(() => {
    const hide = () => { if (document.visibilityState === 'hidden') { setHidden(true); callbacks.current.onClose(); } };
    document.addEventListener('visibilitychange', hide);
    return () => document.removeEventListener('visibilitychange', hide);
  }, []);
  useEffect(() => { if (!props.path) setHidden(false); }, [props.path]);
  useEffect(() => { if (state.phase !== 'ready' && props.path) callbacks.current.onClose(); }, [state.phase, props.path]);
  if (!props.path || hidden || changed || state.phase !== 'ready' || document.visibilityState === 'hidden') return null;
  return <PreviewOpening key={`${state.epoch}:${props.path}`} {...props} path={props.path} />;
}

function PreviewOpening({ path, onClose, onDenied, fileName }: Props & { path: string }) {
  const { session } = useSession();
  const [attempt, setAttempt] = useState(0), [mode, setMode] = useState('loading'), [error, setError] = useState('');
  const [launch, setLaunch] = useState<OfficeLaunch | null>(null), [ready, setReady] = useState(false);
  const [frame, setFrame] = useState<HTMLIFrameElement | null>(null);
  const [legacyFailure, setLegacyFailure] = useState<ApiError | null>(null);
  const stopPreview = useRef<(error: unknown, notifyDenied?: boolean) => void>(() => {});
  const denied = useRef(onDenied); denied.current = onDenied;
  useEffect(() => {
    const controller = new AbortController(); let current: OfficeLaunch | null = null, timer: ReturnType<typeof setTimeout> | undefined;
    let requestTimer: ReturnType<typeof setTimeout> | undefined;
    setMode('loading'); setLaunch(null); setReady(false); setError(''); setLegacyFailure(null);
    const fail = (failure: unknown, notifyDenied = true) => {
      if (controller.signal.aborted) return;
      setLaunch(null); setMode('error'); setError(officeError(failure)); controller.abort(); clearTimeout(timer); clearTimeout(requestTimer);
      if (current) { closeOffice(session, current.sessionId); current = null; }
      if (notifyDenied && failure instanceof ApiError && [401, 403, 404].includes(failure.status)) denied.current?.();
    };
    stopPreview.current = fail;
    const openingRequest = async <T,>(request: () => Promise<T>): Promise<T> => {
      // 超时直接结束显示并取消请求；即使传输稍后返回，下面的代次检查也不能恢复旧实例。
      const deadline = setTimeout(() => fail(new ApiError(0)), 15000);
      requestTimer = deadline;
      try { return await request(); } finally { clearTimeout(deadline); }
    };
    const beat = async () => {
      if (!current || controller.signal.aborted) return;
      try {
        await heartbeat(session, current.sessionId, AbortSignal.any([controller.signal, AbortSignal.timeout(5000)]));
        if (!controller.signal.aborted) timer = setTimeout(() => void beat(), current.heartbeatSeconds * 1000);
      } catch (failure) { fail(failure); }
    };
    void (async () => {
      let info;
      try { info = await openingRequest(() => previewInfo(session, path, controller.signal)); }
      catch (failure) {
        if (controller.signal.aborted) return;
        // 端点不存在不等于文档被删除。未知格式只按受保护文件响应的 MIME 展示 PDF/文本。
        if (failure instanceof ApiError && [404, 405].includes(failure.status)) {
          if (/\.(docx|xlsx|pptx)$/i.test(fileName ?? '')) { fail(failure, false); return; }
          setLegacyFailure(failure); setMode('legacy');
          return;
        }
        throw failure;
      }
      if (controller.signal.aborted) return;
      if (info.mode === 'DIRECT') { setMode('direct'); return; }
      if (info.mode !== 'OFFICE') { setMode('error'); setError(info.mode === 'UNSUPPORTED' ? '该格式不支持在线预览。' : 'Office 预览尚未启用。'); return; }
      const result = await openingRequest(() => launchOffice(session, path, controller.signal));
      if (controller.signal.aborted) { closeOffice(session, result.sessionId); return; }
      current = result; setLaunch(result); setMode('office');
      timer = setTimeout(() => void beat(), result.heartbeatSeconds * 1000);
    })().catch(failure => { if (!isAbort(failure)) fail(failure); });
    return () => { controller.abort(); clearTimeout(timer); clearTimeout(requestTimer); if (current) closeOffice(session, current.sessionId); };
  }, [session, path, fileName, attempt]);

  useEffect(() => {
    if (!launch || !frame) return;
    let active = true;
    const origin = new URL(launch.launchAction).origin, iframe = frame;
    const stop = () => { if (active) stopPreview.current(new ApiError(503)); };
    const timeout = setTimeout(stop, 30000);
    const message = (event: MessageEvent) => {
      if (!active || event.source !== iframe.contentWindow || event.origin !== origin || !event.data
        || event.data.protocolVersion !== 1 || event.data.sessionId !== launch.sessionId) return;
      if (event.data.type === 'kb-office-ready') { clearTimeout(timeout); setReady(true); }
      else if (['kb-office-error', 'kb-office-closed'].includes(event.data.type)) stop();
    };
    window.addEventListener('message', message);
    const form = document.createElement('form'), input = document.createElement('input');
    form.method = 'POST'; form.action = launch.launchAction; form.target = iframe.name; form.style.display = 'none';
    input.type = 'hidden'; input.name = 'launchToken'; input.value = launch.launchToken; form.append(input); document.body.append(form);
    try { form.submit(); } catch { stop(); } finally { input.value = ''; form.remove(); }
    return () => { active = false; clearTimeout(timeout); window.removeEventListener('message', message); };
  }, [launch, frame, session]);
  if (mode === 'direct') return <FilePreview path={path} onClose={onClose} onDenied={onDenied} />;
  if (mode === 'legacy') return <FilePreview path={path} onClose={onClose} onDenied={onDenied}
    onUnsupported={() => stopPreview.current(legacyFailure, false)} />;
  return <Modal title="Office 版本预览" open onCancel={onClose} footer={null} width="96vw" style={{ top: 16 }} destroyOnHidden
    styles={{ body: { height: 'calc(100dvh - 130px)', minHeight: 280, display: 'flex', flexDirection: 'column' } }}>
    {error ? <Alert type="warning" title={error} action={<Button onClick={() => setAttempt(value => value + 1)}>重试预览</Button>} />
      : <><p role="status">{ready ? '文档已就绪 · 只读预览' : '正在打开文档…'}</p>{!ready && <Spin />}
        {launch && <iframe ref={setFrame} name={`office-${launch.sessionId}`} title="Office 只读版本预览" src="about:blank"
          sandbox="allow-scripts allow-same-origin allow-forms" referrerPolicy="no-referrer" allow="clipboard-read 'none'; clipboard-write 'none'"
          style={{ border: 0, flex: 1, width: '100%', minHeight: 0, visibility: ready ? 'visible' : 'hidden' }} />}</>}
  </Modal>;
}
