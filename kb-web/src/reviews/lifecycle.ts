import { computed, ref, watch } from 'vue';
import { useSession } from '../SessionProvider';

/** 隐藏即卸载私有内存；恢复必须先核对身份。会话对象更换也建立新作用域。 */
export function useReviewScope() {
  const auth = useSession();
  const owner = ref(0), visible = ref(document.visibilityState !== 'hidden');
  watch(() => auth.session, (_, previous) => {
    if (previous) {
      owner.value++;
    }
  }, { flush: 'sync' });
  watch(() => auth.session, (session, _, onCleanup) => {
    let generation = 0, live = true;
    const changed = () => {
      const current = ++generation;
      visible.value = false;
      if (document.visibilityState !== 'hidden') {
        void session.refresh().then(() => {
          if (live && current === generation && document.visibilityState !== 'hidden') {
            visible.value = session.getSnapshot().phase === 'ready';
          }
        });
      }
    };
    document.addEventListener('visibilitychange', changed);
    onCleanup(() => {
      live = false;
      generation++;
      document.removeEventListener('visibilitychange', changed);
    });
  }, { immediate: true });
  const active = computed(() => visible.value && document.visibilityState !== 'hidden' && auth.state.phase === 'ready');
  return {
    get active() { return active.value; },
    get key() { return `${owner.value}:${auth.state.epoch}`; },
  };
}
