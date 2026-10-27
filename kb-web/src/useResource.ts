import { useEffect, useRef, useState } from 'react';
import { ApiError, isAbort, messageOf } from './api';

export function useResource<T>(load: (signal: AbortSignal) => Promise<T>, key: string) {
  const latest = useRef(load); latest.current = load;
  const [revision, setRevision] = useState(0);
  type State = { key: string; revision: number; data?: T; error?: string; status?: number; loading: boolean };
  const [state, setState] = useState<State>({ key, revision, loading: true });
  useEffect(() => {
    const controller = new AbortController();
    let active = true;
    setState({ key, revision, loading: true });
    void latest.current(controller.signal).then(data => { if (active && !controller.signal.aborted) setState({ key, revision, data, loading: false }); },
      error => { if (active && !controller.signal.aborted && !isAbort(error)) setState({ key, revision, error: messageOf(error), status: error instanceof ApiError ? error.status : undefined, loading: false }); });
    return () => { active = false; controller.abort(); };
  }, [key, revision]);
  const current: State = state.key === key && state.revision === revision ? state : { key, revision, loading: true };
  return { ...current, reload: () => setRevision(value => value + 1) };
}
