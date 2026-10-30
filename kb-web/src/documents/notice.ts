import type { Session } from '../session';

const notices = new WeakMap<Pick<Session, 'getSnapshot'>, { epoch: number; expires: number }>();
/** 只存当前会话内的无正文短期提示，不写 URL、路由历史或浏览器存储。 */
export function rememberDocumentDeleted(session: Pick<Session, 'getSnapshot'>) {
  const state = session.getSnapshot();
  if (state.phase === 'ready') notices.set(session, { epoch: state.epoch, expires: Date.now() + 10000 });
}
export function consumeDocumentDeleted(session: Pick<Session, 'getSnapshot'>): boolean {
  const notice = notices.get(session); notices.delete(session);
  const state = session.getSnapshot();
  return !!notice && state.phase === 'ready' && state.epoch === notice.epoch && Date.now() < notice.expires;
}
