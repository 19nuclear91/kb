import { computed, onScopeDispose, ref, shallowRef, watch } from 'vue';
import { ApiError, isAbort, messageOf } from '../../api';
import { useSession } from '../../SessionProvider';
import { useAdminAccess } from '../access';
import type { Proof } from './types';

/** 核验只有显式点击才发生；输入变化立即撤销旧证明，不自动重试。 */
export function useVerification<T extends Proof>() {
  const access = useAdminAccess(), auth = useSession();
  let active: AbortController | null = null, generation = 0;
  const data = shallowRef<T>(), error = ref(''), busy = ref(false), now = ref(Date.now());
  onScopeDispose(() => {
    generation++;
    active?.abort();
  });
  watch(data, (value, _, onCleanup) => {
    if (value) {
      const timer = window.setInterval(() => { now.value = Date.now(); }, 500);
      onCleanup(() => window.clearInterval(timer));
    }
  });
  function reset() {
    generation++;
    active?.abort(); active = null;
    data.value = undefined; error.value = ''; busy.value = false;
  }
  async function run(load: (signal: AbortSignal) => Promise<T>) {
    if (active) {
      return;
    }
    const controller = new AbortController(), current = ++generation, session = auth.session, epoch = session.getSnapshot().epoch;
    active = controller; busy.value = true; error.value = ''; data.value = undefined;
    const live = () => current === generation && epoch === session.getSnapshot().epoch && !controller.signal.aborted;
    try {
      const result = await load(controller.signal);
      if (live()) {
        data.value = result; now.value = Date.now();
      }
    } catch (failure) {
      if (live() && !isAbort(failure)) {
        error.value = messageOf(failure);
        if (failure instanceof ApiError && failure.status === 403) {
          access.check(true);
        }
      }
    } finally {
      if (live()) {
        active = null; busy.value = false;
      }
    }
  }
  const valid = computed(() => !!data.value && Date.parse(data.value.expiresAt) > now.value);
  return { get data() { return data.value; }, get error() { return error.value; }, get busy() { return busy.value; }, get valid() { return valid.value; }, run, reset };
}
