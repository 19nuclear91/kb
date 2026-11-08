import { defineStore } from 'pinia';
import { computed, effectScope, markRaw, shallowRef, watch, type EffectScope } from 'vue';
import { adminPaths } from '../admin/api';
import { spacePaths, type AdminSpace } from '../admin/spaceApi';
import type { Session, SessionState } from '../session';
import { useSession } from '../SessionProvider';

export type AccessStatus = 'loading' | 'ready' | 'unavailable';
export interface AdminAccess { status: AccessStatus; spaceStatus: AccessStatus; spaces: readonly AdminSpace[]; factsKey?: string; permissionFactsKey?: string; canManageSpace: (id: string) => boolean; checking: boolean; has: (code: string) => boolean; check: (force?: boolean) => void }
const none: ReadonlySet<string> = new Set();
/** 缺少宿主时一律视为不可确认，不默认放行任何管理能力。 */
export const emptyAdminAccess: AdminAccess = { status: 'unavailable', spaceStatus: 'unavailable', spaces: [], canManageSpace: () => false, checking: false, has: () => false, check: () => {} };

type View = { epoch: number; status: AccessStatus; spaceStatus: AccessStatus; spaces: readonly AdminSpace[]; codes: ReadonlySet<string>; checking: boolean };
const loading = (epoch: number): View => ({ epoch, status: 'loading', spaceStatus: 'loading', spaces: [], codes: none, checking: true });

/**
 * 本人当前管理权限，以会话 epoch 为界；读取失败只影响管理区，ready 的空集合代表确实无权限。
 * 由受保护布局 attach/detach 界定生命周期，对应原 AdminAccessProvider 的挂载范围。
 */
export const useAdminAccessStore = defineStore('adminAccess', () => {
  // 会话经 useSession() 在 attach 时取得（与原 Provider 一致，测试可替换该 facade）。
  let auth: { readonly session: Session; readonly state: Readonly<SessionState> } | null = null;
  const attached = shallowRef(false);
  const view = shallowRef<View>(loading(0));
  let sequence = 0, active: AbortController | null = null, loadSpaces = true, scope: EffectScope | null = null;

  const session = () => auth!.session;
  function check(force = false) {
    if (!attached.value || (active && !force)) {
      return;
    }
    active?.abort();
    const controller = new AbortController(), seq = ++sequence, epoch = session().getSnapshot().epoch, withSpaces = loadSpaces;
    active = controller;
    view.value = loading(epoch);
    let pending = withSpaces ? 2 : 1;
    const apply = (next: Partial<View>) => {
      if (seq !== sequence || epoch !== session().getSnapshot().epoch || controller.signal.aborted) {
        return;
      }
      pending--;
      if (!pending) {
        active = null;
      }
      view.value = { ...view.value, ...next, epoch, checking: pending > 0 };
    };
    void session().json<unknown>(adminPaths.mine, { signal: controller.signal }).then(codes => {
      if (Array.isArray(codes) && codes.every((code): code is string => typeof code === 'string')) {
        apply({ status: 'ready', codes: new Set(codes) });
      } else {
        apply({ status: 'unavailable', codes: none });
      }
    }, () => apply({ status: 'unavailable', codes: none }));
    if (!withSpaces) {
      view.value = { ...view.value, spaceStatus: 'ready', spaces: [] };
    } else {
      void session().json<unknown>(spacePaths.spaces, { signal: controller.signal }).then(result => {
        if (Array.isArray(result) && result.every(space => space && typeof space.spaceId === 'string' && typeof space.manageable === 'boolean')) {
          apply({ spaceStatus: 'ready', spaces: (result as AdminSpace[]).filter(space => space.manageable) });
        } else {
          apply({ spaceStatus: 'unavailable', spaces: [] });
        }
      }, () => apply({ spaceStatus: 'unavailable', spaces: [] }));
    }
  }
  function cancel() {
    sequence++;
    active?.abort();
    active = null;
  }
  function detach() {
    scope?.stop();
    scope = null;
    cancel();
    attached.value = false;
  }
  /** 布局挂载时调用；loadSpaces 变化或会话 epoch 变化时作废旧核对并重新读取。 */
  function attach(options: () => { loadSpaces: boolean }) {
    detach();
    const current = useSession();
    auth = current;
    attached.value = true;
    scope = effectScope(true);
    scope.run(() => {
      watch([() => current.session, () => options().loadSpaces, () => current.state.epoch], (_, __, onCleanup) => {
        loadSpaces = options().loadSpaces;
        check();
        onCleanup(cancel);
      }, { immediate: true });
    });
    return detach;
  }

  const access = computed<AdminAccess>(() => {
    if (!attached.value || !auth) {
      return emptyAdminAccess;
    }
    const epoch = auth.state.epoch;
    const current = view.value.epoch === epoch ? view.value : loading(epoch);
    return markRaw({
      status: current.status, spaceStatus: current.spaceStatus, spaces: current.spaces,
      factsKey: JSON.stringify([[...current.codes].sort(), current.spaces.map(space => `${space.spaceId}:${space.archived}`).sort()]),
      // 功能权限消费者不应因空间目录的加载、清空或恢复而失败旧状态。
      permissionFactsKey: JSON.stringify([...current.codes].sort()),
      canManageSpace: (id: string) => current.spaceStatus === 'ready' && current.spaces.some(space => space.spaceId === id),
      checking: current.checking, has: (code: string) => current.status === 'ready' && current.codes.has(code), check,
    });
  });
  return { access, attach, detach };
});
