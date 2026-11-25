import { computed, inject, onScopeDispose, provide, shallowRef, watch, type ComputedRef, type InjectionKey, type ShallowRef } from 'vue';
import type { Acl, AclPermission, Principal } from './spaceApi';

export interface AclFormRecovery {
  spaceId: string;
  dialog: 'grant' | 'revoke-batch' | Acl;
  principal: Principal | null;
  permission: AclPermission;
}
interface RecoveryScope { pending: ShallowRef<AclFormRecovery | null>; ready: ComputedRef<boolean> }
const key: InjectionKey<RecoveryScope> = Symbol('AclFormRecovery');

/** 仅资源详情页存活期间保留一次重核中的正式表单，不保留诊断数据或写入回执。 */
export function provideAclFormRecovery(context: () => { identity: string; spaceId: string | null; checking: boolean; allowed: boolean }) {
  const pending = shallowRef<AclFormRecovery | null>(null);
  const ready = computed(() => { const current = context(); return !current.checking && current.allowed; });
  watch(context, (current, previous) => {
    if (previous && previous.identity !== current.identity || !current.checking && (!current.allowed || current.spaceId !== pending.value?.spaceId)) pending.value = null;
  }, { flush: 'sync' });
  onScopeDispose(() => { pending.value = null; });
  const scope = { pending, ready };
  provide(key, scope);
  return scope;
}

export function useAclFormRecovery() {
  return inject(key, { pending: shallowRef<AclFormRecovery | null>(null), ready: computed(() => true) });
}
