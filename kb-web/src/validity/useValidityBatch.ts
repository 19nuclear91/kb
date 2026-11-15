import { computed, shallowRef, toValue, watch, type MaybeRefOrGetter } from 'vue';
import { useSession } from '../SessionProvider';
import { pairKey, ValidityBatch, type Badge, type Pair } from './api';

export function useValidityBatch(pairs: MaybeRefOrGetter<Pair[]>, context: MaybeRefOrGetter<string>) {
  const auth = useSession(), state = shallowRef({ badges: new Map<string, Badge>(), unavailable: false });
  watch([() => toValue(context), () => [...new Set(toValue(pairs).map(pairKey))].sort().join(','), () => auth.state.epoch, () => auth.state.phase], (_, __, cleanup) => {
    const batch = new ValidityBatch(auth.session); let live = true;
    state.value = { badges: new Map(), unavailable: false };
    if (auth.state.phase === 'ready' && toValue(pairs).length) {
      void batch.load(toValue(pairs)).then(badges => { if (live && badges) state.value = { badges, unavailable: false }; },
        () => { if (live) state.value = { badges: new Map(), unavailable: true }; });
    }
    cleanup(() => { live = false; batch.clear(); });
  }, { immediate: true, flush: 'sync' });
  return { badges: computed(() => state.value.badges), unavailable: computed(() => state.value.unavailable) };
}
