import { computed, watch } from 'vue';
import { useSession } from '../SessionProvider';
import { useExternalStore } from '../useExternalStore';
import { pollerFor } from './unreadPoller';

export { DEFAULT_POLL_MS, MIN_POLL_MS, pollInterval, pollerFor, UnreadPoller, type UnreadState } from './unreadPoller';

/** 当前身份确认后才挂载订阅，返回服务器确认的计数与显式刷新入口。 */
export function useUnreadCount() {
  const auth = useSession();
  const poller = computed(() => pollerFor(auth.session));
  const value = useExternalStore(() => poller.value);
  watch([poller, () => auth.state.epoch, () => auth.state.phase === 'ready'], ([current, epoch, ready], _, onCleanup) => {
    onCleanup(current.attach(epoch, ready));
  }, { immediate: true });
  return {
    get state() { return value.value; },
    refresh: () => poller.value.refresh(),
  };
}
