import { computed, inject, provide, ref, toValue, watch, type InjectionKey, type MaybeRefOrGetter } from 'vue';
import type { Binding, WriteBindings } from '../admin/hooks';
import type { CommentItem } from './api';
import type { CommentList } from './hooks';

export const DENIED = new Set([401, 403, 404]);
export const failureText = (status?: number) => status === 400 ? '评论请求不符合要求，请刷新后重试。' : '评论读取失败，暂不能确认讨论内容；这不表示该版本没有评论。';
export const when = (value: string) => new Date(value).toLocaleString('zh-CN');
export const composerScope = (documentId: string, versionId: string, parent: string | null) => `comment:${documentId}/${versionId}/${parent ?? 'root'}`;
export interface Thread {
  documentId: string; versionId: string; deny: () => void; own: Set<string>;
  copies: (origin: { parentId: string | null; body: string }) => readonly Binding[];
  erased: (id: string, copies?: readonly Binding[]) => void;
  writes: WriteBindings; round: number; recheck: () => void;
}
const key: InjectionKey<Thread> = Symbol('comment-thread');
export function provideThread(thread: Thread) {
  provide(key, thread);
}
export function useThread(): Thread {
  const thread = inject(key);
  if (!thread) {
    throw new Error('缺少评论上下文');
  }
  return thread;
}
export function useDenial(list: CommentList, deny: () => void) {
  watch([() => list.failed, () => list.status], ([failed, status]) => {
    if (failed && DENIED.has(status ?? 0)) {
      deny();
    }
  });
}
export function useOwn(items: () => CommentItem[], own: Set<string>) {
  watch(items, values => {
    for (const item of values) {
      if (item.isOwn && !item.deleted) {
        own.add(item.commentId);
      } else {
        own.delete(item.commentId);
      }
    }
  }, { immediate: true });
}
export function useRecheck(list: CommentList, round: MaybeRefOrGetter<number>) {
  const seen = ref(toValue(round));
  watch(() => toValue(round), value => {
    if (seen.value !== value) {
      seen.value = value;
      list.reload();
    }
  });
  return computed(() => seen.value !== toValue(round));
}
export function holdReason(list: CommentList, rechecking: boolean): string {
  if (rechecking || list.revalidating) {
    return '正在重新核对发表资格…';
  }
  if (list.failed) {
    return '读取未完成，请在列表末尾重新读取后再发表。';
  }
  if (list.incomplete) {
    return '正在重新读取，完成后才能发表。';
  }
  return '';
}
