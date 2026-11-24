import { computed, inject, provide, ref, type InjectionKey } from 'vue';
import { useSession } from '../SessionProvider';
import { perm, useAdminAccess } from '../admin/access';

interface TermReadAccess { readonly active: boolean; readonly denied: boolean; reject: () => void; retry: () => void }
const key: InjectionKey<TermReadAccess> = Symbol('term-read-access');
// 只保留本会话代次的资格拒绝标记，不保存词条或查询；同资格重挂载不循环自动读取。
const deniedReads = new WeakMap<object, { epoch: number; facts: Set<string> }>();
const unavailable: TermReadAccess = { active: false, denied: false, reject: () => {}, retry: () => {} };

/** 所有词典管理读取共用同一拒绝边界，先使私有状态失效，再有界重核当前资格。 */
export function provideTermReadAccess(): TermReadAccess {
  const auth = useSession(), access = useAdminAccess(), turn = ref(0);
  const facts = () => {
    let record = deniedReads.get(auth.session);
    if (!record || record.epoch !== auth.state.epoch) {
      record = { epoch: auth.state.epoch, facts: new Set() };
      deniedReads.set(auth.session, record);
    }
    return record.facts;
  };
  const current = computed(() => access.permissionFactsKey ?? access.factsKey ?? '');
  const denied = computed(() => { turn.value; return facts().has(current.value); });
  const boundary: TermReadAccess = {
    get active() { return !denied.value && access.has(perm.termRead); },
    get denied() { return denied.value; },
    reject() {
      if (denied.value) return;
      facts().add(current.value);
      turn.value++;
      access.check(true);
    },
    retry() {
      if (access.checking) return;
      const rejected = facts(), at = current.value;
      // check 会先同步清空资格，解除标记不会在重核完成前启用旧目录。
      access.check(true);
      rejected.delete(at);
      turn.value++;
    },
  };
  provide(key, boundary);
  return boundary;
}

export const useTermReadAccess = (): TermReadAccess => inject(key, unavailable);
