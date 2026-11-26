import { computed, effectScope, watch, type EffectScope } from 'vue';
import { useSession } from '../SessionProvider';
import { perm, useAdminAccess } from '../admin/access';
import { cardCommands } from './commands';
import type { Session } from '../session';

const observers = new WeakMap<Session, { epoch: number; scope: EffectScope }>();

/** 资格监听随登录会话保留，避免路由因权限重核卸载后漏掉随后确认的撤权。 */
export function useCardCommands() {
  const auth = useSession(), access = useAdminAccess(), commands = computed(() => cardCommands(auth.session));
  watch([() => auth.session, () => auth.state.epoch, () => auth.state.phase], () => {
    if (auth.state.phase !== 'ready') return;
    const session = auth.session, epoch = auth.state.epoch, previous = observers.get(session);
    if (previous?.epoch === epoch && previous.scope.active) return;
    previous?.scope.stop();
    const scope = effectScope(true), observer = { epoch, scope }, store = cardCommands(session);
    observers.set(session, observer);
    scope.run(() => {
      watch([() => auth.session, () => auth.state.epoch, () => auth.state.phase,
        () => access.status, () => access.spaceStatus, () => access.permissionFactsKey, () => access.factsKey], () => {
        if (auth.session !== session || auth.state.epoch !== epoch || auth.state.phase !== 'ready') {
          store.revoke(() => false);
          scope.stop();
          if (observers.get(session) === observer) observers.delete(session);
          return;
        }
        if (access.status !== 'ready') return;
        store.revoke(intent => {
          if (intent.operation === 'ANSWER_CARD_STEWARDSHIP_UPDATE') return access.spaceStatus !== 'ready'
            || access.has(perm.spaceManageAll) || access.canManageSpace(String(intent.payload.spaceId));
          return access.has(intent.operation === 'ANSWER_CARD_APPROVE' || intent.operation === 'ANSWER_CARD_REJECT' ? perm.answerCardReview : perm.answerCardWrite);
        });
      }, { immediate: true, flush: 'sync' });
    });
  }, { immediate: true, flush: 'sync' });
  return commands;
}
