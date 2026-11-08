import { computed, shallowReactive, toValue, type MaybeRefOrGetter } from 'vue';
import { positiveId } from '../api';
import { useSession } from '../SessionProvider';
import type { WriteBindings } from './hooks';

export function validTarget(value: string): boolean {
  try {
    positiveId(value); return true;
  } catch {
    return false;
  }
}
const notices = new WeakMap<object, { epoch: number; values: Map<string, string> }>();
/** 资源反馈在同一身份下的资格重核期间保留，身份切换立即清空。 */
export function useResourceNotice(target: MaybeRefOrGetter<string>) {
  const auth = useSession();
  const store = () => {
    let record = notices.get(auth.session);
    if (!record || record.epoch !== auth.state.epoch) {
      record = { epoch: auth.state.epoch, values: shallowReactive(new Map<string, string>()) };
      notices.set(auth.session, record);
    }
    return record.values;
  };
  return computed({ get: () => store().get(toValue(target)) ?? '', set: (text: string) => { store().set(toValue(target), text); } });
}
export const useResourceReceipt = useResourceNotice;
const resourceWrites = new WeakMap<object, { epoch: number; bindings: WriteBindings }>();
/** 仅追踪表项增删，Binding 不代理，以保持 sending WeakMap 的对象身份。 */
export function useResourceWriteBindings(): WriteBindings {
  const auth = useSession();
  let record = resourceWrites.get(auth.session);
  if (!record || record.epoch !== auth.state.epoch) {
    record?.bindings.clear();
    record = { epoch: auth.state.epoch, bindings: shallowReactive(new Map()) };
    resourceWrites.set(auth.session, record);
  }
  return record.bindings;
}
export const fieldValue = (data: FormData, name: string) => String(data.get(name) ?? '');
