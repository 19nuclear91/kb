import { computed } from 'vue';
import { useSession } from '../SessionProvider';
import { useExternalStore } from '../useExternalStore';
import { getSsoFlow } from './flow';

export function useSso() {
  const auth = useSession();
  const flow = computed(() => getSsoFlow(auth.session));
  const state = useExternalStore(() => flow.value);
  return {
    get flow() { return flow.value; },
    get state() { return state.value; },
  };
}
