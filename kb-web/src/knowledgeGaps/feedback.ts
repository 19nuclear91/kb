import { onScopeDispose, shallowRef, toValue, watch, type MaybeRefOrGetter } from 'vue';
import { useSession } from '../SessionProvider';
import type { Turn } from '../types';

/** 仅取持久轮次的本人原提问，既不读取回答/引用，也不输出轮次或会话身份。 */
export function savedFeedbackQuestion(turn: Pick<Turn, 'question' | 'status'>): string | null {
  return ['COMPLETED', 'FAILED', 'CANCELLED', 'INTERRUPTED'].includes(turn.status) && typeof turn.question === 'string' ? turn.question : null;
}
/** 来源页只持有一段预填输入；弹窗未打开不加载表单，取消/隐藏/换号/目标变化即清空。 */
export function useGapFeedback(target: MaybeRefOrGetter<string> = '') {
  const auth = useSession(), description = shallowRef<string | null>(null);
  const close = () => { description.value = null; };
  watch([() => auth.session, () => auth.state.epoch, () => auth.state.phase, () => toValue(target)], close, { flush: 'sync' });
  const hidden = () => { if (document.visibilityState === 'hidden') close(); };
  document.addEventListener('visibilitychange', hidden);
  onScopeDispose(() => { close(); document.removeEventListener('visibilitychange', hidden); });
  return {
    get description() { return description.value; }, close,
    open(value: string) { if (auth.state.phase === 'ready' && document.visibilityState !== 'hidden') description.value = value; },
  };
}
