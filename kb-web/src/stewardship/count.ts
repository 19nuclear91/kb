import { computed, watch } from 'vue';
import { useSession } from '../SessionProvider';
import { useExternalStore } from '../useExternalStore';
import { pollerFor } from './poller';

export function useStewardshipCount() {
  const auth = useSession(), poller = computed(() => pollerFor(auth.session)), value = useExternalStore(() => poller.value);
  watch([poller, () => auth.state.epoch, () => auth.state.phase === 'ready'], ([current, epoch, ready], _, cleanup) => { cleanup(current.attach(epoch, ready)); }, { immediate: true });
  return { get state() { return value.value; }, refresh: () => poller.value.refresh() };
}
