import { ApiError } from '../api';
import type { Session } from '../session';
import { importApi, importMessage, type ImportItem, type Receipt } from './api';

export interface QueueRow { item: ImportItem; phase: 'idle' | 'sending' | 'unknown' | 'settled'; selected: boolean; message: string }
export interface QueueState { rows: QueueRow[]; running: boolean; active: number; notice: string; denied: boolean }
export type QueueApi = Pick<typeof importApi, 'upload' | 'item'>;
export const minimalItem = (item: ImportItem): ImportItem => ({ ...item, file: undefined, canUpload: false });
export class ImportQueue {
  private rows = new Map<string, QueueRow>();
  private files = new Map<string, File>();
  private listeners = new Set<() => void>();
  private pending: string[] = [];
  private controller = new AbortController();
  private running = false;
  private active = 0;
  private denied = false;
  private accessGeneration = 0;
  private notice = '';
  private state: QueueState = { rows: [], running: false, active: 0, notice: '', denied: false };
  private epoch: number;
  private unsubscribe: () => void;
  constructor(private session: Session, readonly importId: string, private concurrency = 2, private api: QueueApi = importApi) {
    this.concurrency = Math.min(2, Math.max(1, concurrency)); this.epoch = session.getSnapshot().epoch;
    this.unsubscribe = session.subscribe(() => { if (!this.valid()) this.dispose(); });
  }
  getSnapshot = () => this.state;
  subscribe = (listener: () => void) => { this.listeners.add(listener); return () => { this.listeners.delete(listener); }; };
  private valid() { return !this.controller.signal.aborted && this.session.getSnapshot().epoch === this.epoch && this.session.getSnapshot().phase === 'ready'; }
  private publish() { this.state = { rows: [...this.rows.values()], running: this.running, active: this.active, notice: this.notice, denied: this.denied }; this.listeners.forEach(fn => fn()); }
  private update(itemId: string, values: Partial<QueueRow>) { const old = this.rows.get(itemId); if (old) this.rows.set(itemId, { ...old, ...values }); }
  captureAccess() { return this.accessGeneration; }
  isAccessCurrent(generation: number) { return this.valid() && generation === this.accessGeneration; }
  private project(item: ImportItem, generation: number) {
    return this.denied || !this.isAccessCurrent(generation) ? minimalItem(item) : item;
  }
  setItems(items: ImportItem[], generation = this.captureAccess()) {
    if (!this.valid()) return;
    if (items.some(item => item.importId !== this.importId)) throw new ApiError(503);
    // 披露资格独立于状态 revision，任何最小投影都先撤销整个任务的旧清单。
    if (items.some(item => !item.file)) this.revoke();
    for (const item of items) {
      const old = this.rows.get(item.itemId); if (old && old.item.revision > item.revision) continue;
      if (old?.item.state === 'ACCEPTED' && item.state !== 'ACCEPTED') continue;
      const phase = old?.phase === 'sending' ? 'sending' : item.state === 'ACCEPTED' ? 'settled' : old?.phase ?? 'idle';
      this.rows.set(item.itemId, { item: this.project(item, generation), phase, selected: this.files.has(item.itemId), message: old?.message ?? '' });
    }
    this.publish();
  }
  /** 只有摘要匹配完成的本地文件才能交给队列；文件选择本身不调度。 */
  attach(itemId: string, file: File, generation = this.captureAccess()) {
    const row = this.rows.get(itemId);
    if (!this.isAccessCurrent(generation) || this.denied || !row?.item.canUpload || !row.item.file || row.phase === 'sending' || row.phase === 'unknown') return;
    this.files.set(itemId, file); this.update(itemId, { selected: true, message: '' }); this.publish();
  }
  start(ids?: string[]) {
    if (!this.valid() || this.denied || this.running || this.active > 0) return;
    this.pending = (ids ?? [...this.rows.keys()]).filter(id => { const row = this.rows.get(id); return row && row.phase !== 'unknown' && row.item.canUpload && this.files.has(id); });
    this.pending = [...new Set(this.pending)]; this.running = true; this.notice = ''; this.pump();
  }
  pause() { this.running = false; this.pending = []; this.publish(); }
  setConcurrency(value: number) { this.concurrency = Math.min(2, Math.max(1, value)); }
  /** 只有完整资格核对仍属于当前代次，才能恢复披露；不会恢复已清理的文件或调度。 */
  authorize(generation: number) {
    if (!this.isAccessCurrent(generation)) return false;
    this.denied = false; this.publish(); return true;
  }
  revoke() {
    if (!this.valid()) return;
    this.accessGeneration++;
    this.denied = true; this.running = false; this.pending = []; this.files.clear();
    for (const [id, row] of this.rows) this.rows.set(id, { ...row, item: minimalItem(row.item), selected: false });
    this.publish();
  }
  async check(itemId: string): Promise<ImportItem | null> {
    if (!this.valid()) return null;
    const generation = this.captureAccess();
    let latest: ImportItem;
    try { latest = await this.api.item(this.session, this.importId, itemId, this.controller.signal); }
    catch (error) {
      if (this.valid() && error instanceof ApiError && [401, 403].includes(error.status)) { this.revoke(); this.notice = importMessage(error); this.publish(); }
      throw error;
    }
    if (!this.valid()) return null;
    if (!latest.file) this.revoke();
    const old = this.rows.get(itemId);
    if (!old || latest.revision < old.item.revision) throw new ApiError(503);
    if (old.item.state === 'ACCEPTED' && latest.state !== 'ACCEPTED') throw new ApiError(503);
    if (latest.state === 'ACCEPTED') this.files.delete(itemId);
    const projected = this.project(latest, generation);
    this.update(itemId, { item: projected,
      phase: latest.state === 'PROCESSING' ? 'unknown' : latest.state === 'ACCEPTED' ? 'settled' : 'idle',
      selected: this.files.has(itemId), message: latest.state === 'PROCESSING' ? '服务端处理中，请继续核对' : '' });
    this.publish(); return projected;
  }
  private pump() {
    if (!this.valid()) return;
    while (this.running && this.active < this.concurrency && this.pending.length) {
      const id = this.pending.shift()!; this.active++; this.update(id, { phase: 'sending', message: '' }); void this.send(id);
    }
    if (!this.pending.length && this.active === 0) this.running = false;
    this.publish();
  }
  private async send(id: string) {
    const generation = this.captureAccess();
    try {
      const file = this.files.get(id); if (!file) return;
      const latest = await this.api.item(this.session, this.importId, id, this.controller.signal);
      if (!this.valid()) return;
      if (!latest.file) this.revoke();
      const row = this.rows.get(id)!;
      if (latest.revision < row.item.revision) throw new ApiError(503);
      if (row.item.state === 'ACCEPTED' && latest.state !== 'ACCEPTED') throw new ApiError(503);
      if (latest.state === 'ACCEPTED' || !latest.canUpload || this.denied || !this.isAccessCurrent(generation)) {
        if (latest.state === 'ACCEPTED') this.files.delete(id);
        this.update(id, { item: this.project(latest, generation), selected: this.files.has(id), phase: latest.state === 'PROCESSING' ? 'unknown' : 'settled' }); return;
      }
      if (!this.running) { this.update(id, { item: latest, phase: 'idle' }); return; }
      if (latest.file?.sha256 !== row.item.file?.sha256 || latest.file?.fileName !== row.item.file?.fileName || latest.file?.sizeBytes !== row.item.file?.sizeBytes) throw new ApiError(503);
      this.update(id, { item: latest });
      const receipt: Receipt = await this.api.upload(this.session, latest, file, crypto.randomUUID(), this.controller.signal);
      if (!this.valid()) return;
      const current = this.rows.get(id)!.item;
      const item = current.state === 'ACCEPTED' ? current : { ...current, state: 'ACCEPTED' as const, canUpload: false, receipt, failureCode: undefined };
      this.update(id, { item: this.project(item, generation), phase: 'settled', selected: false }); this.files.delete(id);
    } catch (error) {
      if (!this.valid()) return;
      const status = error instanceof ApiError ? error.status : 0;
      if ([401, 403, 429].includes(status)) { this.pause(); this.notice = importMessage(error); }
      if ([401, 403].includes(status)) this.revoke();
      this.update(id, { phase: 'unknown', message: importMessage(error) }); this.publish();
      try { await this.check(id); } catch { /* 保留待确认，由有界核对或用户主动刷新接续。 */ }
    } finally {
      this.active--; if (this.valid()) this.pump();
    }
  }
  dispose() {
    if (this.controller.signal.aborted) return;
    this.controller.abort(); this.unsubscribe(); this.running = false; this.pending = []; this.files.clear(); this.rows.clear(); this.notice = '';
    this.publish(); this.listeners.clear();
  }
}
