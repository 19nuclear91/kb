import { inject, provide, type InjectionKey } from 'vue';
import { useExternalStore, type ExternalStore } from '../useExternalStore';
import { DocumentOperations, idleOperations, type OperationState } from './operations';

/** 详情页提供本文档的写入协调；以取值函数注入，详情切换文档时子树读取的是当前实例。 */
const DocumentOperationKey: InjectionKey<() => DocumentOperations | null> = Symbol('document-operations');
const idle: ExternalStore<OperationState> = { subscribe: () => () => {}, getSnapshot: () => idleOperations };

export function provideDocumentOperations(read: () => DocumentOperations | null) {
  provide(DocumentOperationKey, read);
}

export function useDocumentOperations(): { readonly operations: DocumentOperations | null; readonly state: OperationState } {
  const read = inject(DocumentOperationKey, () => null);
  const state = useExternalStore(() => read() ?? idle);
  return {
    get operations() { return read(); },
    get state() { return state.value; },
  };
}
