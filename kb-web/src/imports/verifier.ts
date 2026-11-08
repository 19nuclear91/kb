import { ImportQueue } from './queue';

interface Watch { started: number; failures: number; timer?: ReturnType<typeof setTimeout>; busy: boolean; stopped: boolean }
/** 每项单次核对窗口最多三分钟，连续三次读取失败停止；不发送文件。 */
export class ImportVerifier {
  private watches = new Map<string, Watch>();
  private disposed = false;
  private unsubscribe: () => void;
  constructor(private queue: ImportQueue, private now = () => Date.now(), private visible = () => document.visibilityState !== 'hidden') {
    this.unsubscribe = queue.subscribe(() => this.sync()); this.sync();
  }
  private sync() {
    if (this.disposed || !this.visible()) return;
    for (const row of this.queue.getSnapshot().rows) {
      if (row.phase === 'sending') {
        const old = this.watches.get(row.item.itemId);
        if (old && !old.busy) { clearTimeout(old.timer); this.watches.delete(row.item.itemId); }
        continue;
      }
      if ((row.phase === 'unknown' || row.item.state === 'PROCESSING') && !this.watches.has(row.item.itemId)) {
        const watch: Watch = { started: this.now(), failures: 0, busy: false, stopped: false };
        this.watches.set(row.item.itemId, watch); this.schedule(row.item.itemId, watch);
      }
    }
  }
  private schedule(id: string, watch: Watch) {
    if (this.disposed || watch.stopped) return;
    if (this.now() - watch.started >= 180000 || watch.failures >= 3) { watch.stopped = true; return; }
    watch.timer = setTimeout(() => { void this.run(id, watch); }, 3000);
  }
  private async run(id: string, watch: Watch) {
    watch.timer = undefined;
    if (this.disposed || !this.visible() || watch.busy || this.now() - watch.started >= 180000) { watch.stopped = true; return; }
    const row = this.queue.getSnapshot().rows.find(row => row.item.itemId === id);
    if (!row || (row.phase !== 'unknown' && row.item.state !== 'PROCESSING')) { watch.stopped = true; return; }
    if (row.phase === 'sending') { this.schedule(id, watch); return; }
    watch.busy = true;
    try {
      const latest = await this.queue.check(id); watch.failures = 0;
      if (!latest || latest.state !== 'PROCESSING') watch.stopped = true;
    } catch { watch.failures++; }
    finally { watch.busy = false; this.schedule(id, watch); }
  }
  async manual(id: string) {
    if (this.disposed || !this.visible()) return;
    const old = this.watches.get(id); if (old?.busy) return;
    if (old?.timer) clearTimeout(old.timer);
    const watch: Watch = { started: this.now(), failures: 0, busy: true, stopped: false }; this.watches.set(id, watch);
    try { const latest = await this.queue.check(id); if (!latest || latest.state !== 'PROCESSING') watch.stopped = true; }
    catch (error) { watch.failures++; throw error; }
    finally { watch.busy = false; this.schedule(id, watch); }
  }
  dispose() { this.disposed = true; this.unsubscribe(); this.watches.forEach(watch => clearTimeout(watch.timer)); this.watches.clear(); }
}
