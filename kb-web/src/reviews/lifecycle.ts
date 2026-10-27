import { useEffect, useRef, useState } from 'react';
import { useSession } from '../SessionProvider';

/** 隐藏即卸载私有内存；恢复必须先核对身份。会话对象更换也建立新作用域。 */
export function useReviewScope() {
  const { session, state } = useSession();
  const owner = useRef({ session, generation: 0 });
  if (owner.current.session !== session) owner.current = { session, generation: owner.current.generation + 1 };
  const [visible, setVisible] = useState(document.visibilityState !== 'hidden');
  useEffect(() => {
    let generation = 0, live = true;
    const changed = () => {
      const current = ++generation;
      setVisible(false);
      if (document.visibilityState !== 'hidden') {
        void session.refresh().then(() => {
          if (live && current === generation && document.visibilityState !== 'hidden') setVisible(session.getSnapshot().phase === 'ready');
        });
      }
    };
    document.addEventListener('visibilitychange', changed);
    return () => { live = false; generation++; document.removeEventListener('visibilitychange', changed); };
  }, [session]);
  return { active: visible && document.visibilityState !== 'hidden' && state.phase === 'ready', key: `${owner.current.generation}:${state.epoch}` };
}
