import { useEffect, useState } from 'react';
import { useSession } from '../SessionProvider';
import { ApiError, isAbort, messageOf, positiveId } from '../api';
import { pendingProgress } from '../logic';
import type { Progress } from '../types';

export function useProgress(documentId: string, versionId: string, revision = 0) {
  const { session, state: identity } = useSession();
  const key = `${identity.epoch}/${documentId}/${versionId}/${revision}`;
  const [state, setState] = useState<{ key: string; data?: Progress; error?: string; loading: boolean; denied?: boolean }>({ key, loading: true });
  useEffect(() => {
    const controller = new AbortController(); let timer: ReturnType<typeof setTimeout> | undefined, failures = 0;
    const stop = () => { if (document.visibilityState === 'hidden') { controller.abort(); clearTimeout(timer); setState({ key, loading: false }); } };
    document.addEventListener('visibilitychange', stop);
    setState({ key, loading: true });
    const load = async () => {
      try {
        const data = await session.json<Progress>(`/api/documents/${positiveId(documentId)}/progress?versionId=${positiveId(versionId)}`, { signal: controller.signal });
        if (controller.signal.aborted) return;
        failures = 0; setState({ key, data, loading: false });
        if (pendingProgress(data)) timer = setTimeout(() => void load(), 3000);
      } catch (error) {
        if (controller.signal.aborted || isAbort(error)) return;
        const denied = error instanceof ApiError && [401, 403, 404].includes(error.status);
        setState({ key, error: messageOf(error), loading: false, denied });
        if (!denied && ++failures < 3) timer = setTimeout(() => void load(), 3000 * failures);
      }
    };
    if (identity.phase === 'ready' && document.visibilityState !== 'hidden') void load();
    return () => { controller.abort(); clearTimeout(timer); document.removeEventListener('visibilitychange', stop); };
  }, [key, documentId, versionId, session]);
  return state.key === key ? state : { key, loading: true, data: undefined, error: undefined, denied: false };
}
