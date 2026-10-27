import { useMemo, useSyncExternalStore } from 'react';
import { useSession } from '../SessionProvider';
import { getSsoFlow } from './flow';

export function useSso() {
  const { session } = useSession();
  const flow = useMemo(() => getSsoFlow(session), [session]);
  const state = useSyncExternalStore(flow.subscribe, flow.getSnapshot);
  return { flow, state };
}
