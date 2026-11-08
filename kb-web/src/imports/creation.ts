import type { Session } from '../session';
import { createIntent, importApi, importMessage, type Capabilities, type CreateIntent, type Manifest } from './api';

export interface CreationState { phase: 'empty' | 'creating' | 'unknown' | 'ready'; importId: string | null; notice: string }
export class ImportCreation {
  private state: CreationState = { phase: 'empty', importId: null, notice: '' };
  private listeners = new Set<() => void>();
  private intent: CreateIntent | null = null;
  private files = new Map<string, File>();
  private controller = new AbortController();
  private busy = false;
  private epoch: number;
  private unsubscribe: () => void;
  constructor(private session: Session, private api: Pick<typeof importApi, 'create' | 'resolve'> = importApi) {
    this.epoch = session.getSnapshot().epoch;
    this.unsubscribe = session.subscribe(() => { if (!this.valid()) this.dispose(); });
  }
  getSnapshot = () => this.state;
  subscribe = (fn: () => void) => { this.listeners.add(fn); return () => { this.listeners.delete(fn); }; };
  private valid() { return !this.controller.signal.aborted && this.epoch === this.session.getSnapshot().epoch && this.session.getSnapshot().phase === 'ready'; }
  private publish(value: CreationState) { this.state = value; this.listeners.forEach(fn => fn()); }
  begin(manifest: Manifest, capabilities: Capabilities, files: Map<string, File>) {
    if (!this.valid() || this.intent || this.busy) return Promise.resolve();
    this.intent = createIntent(manifest, capabilities); this.files = new Map(files); return this.retry();
  }
  async retry() {
    if (!this.valid() || !this.intent || this.busy || this.state.phase === 'ready') return;
    this.busy = true; this.publish({ phase: 'creating', importId: null, notice: '' });
    try { const value = await this.api.create(this.session, this.intent, this.controller.signal);
      if (this.valid()) this.publish({ phase: 'ready', importId: value.importId, notice: '' });
    } catch (error) { if (this.valid()) this.publish({ phase: 'unknown', importId: null, notice: importMessage(error) }); }
    finally { this.busy = false; }
  }
  async resolve() {
    if (!this.valid() || !this.intent || this.busy || this.state.phase === 'ready') return;
    this.busy = true;
    try { const id = await this.api.resolve(this.session, this.intent.requestId, this.controller.signal);
      if (this.valid()) this.publish(id ? { phase: 'ready', importId: id, notice: '' }
        : { phase: 'unknown', importId: null, notice: '尚未查得已提交批次；原请求可能仍在途，可稍后核对或以原意图主动重试。' });
    } catch (error) { if (this.valid()) this.publish({ phase: 'unknown', importId: null, notice: importMessage(error) }); }
    finally { this.busy = false; }
  }
  takeFiles(): Map<string, File> {
    if (!this.valid() || this.state.phase !== 'ready') return new Map();
    const files = this.files; this.files = new Map(); return files;
  }
  dispose() {
    if (this.controller.signal.aborted) return;
    this.controller.abort(); this.unsubscribe(); this.intent = null; this.files.clear();
    this.publish({ phase: 'empty', importId: null, notice: '' }); this.listeners.clear();
  }
}
