import { ApiError } from '../api';

export type OperationOutcome = 'confirmed' | 'rejected' | 'uncertain';
export interface OperationState { pending: number; exclusive: boolean; uncertain: number; uncertaintyRevision: number }
export const idleOperations: OperationState = { pending: 0, exclusive: false, uncertain: 0, uncertaintyRevision: 0 };

export function operationFailure(error: unknown): OperationOutcome {
  return error instanceof ApiError && [400, 401, 403, 404, 409, 413, 415, 422, 429].includes(error.status) ? 'rejected' : 'uncertain';
}

/** 详情页持有；在途登记独立于子组件挂载，不把本地取消当作服务端撤销。 */
export class DocumentOperations {
  private state: OperationState = idleOperations;
  private writes = new Map<string, symbol>();
  private unknown = new Set<string>();
  private listeners = new Set<() => void>();

  getSnapshot = () => this.state;
  subscribe = (listener: () => void) => { this.listeners.add(listener); return () => { this.listeners.delete(listener); }; };
  private publish(update: Partial<OperationState> = {}) {
    this.state = { ...this.state, ...update, pending: this.writes.size, uncertain: this.unknown.size };
    this.listeners.forEach(listener => listener());
  }

  beginWrite(key: string): { settle: (outcome: OperationOutcome) => void } | null {
    if (this.state.exclusive || this.writes.has(key)) return null;
    const token = Symbol(key);
    this.writes.set(key, token); this.publish();
    return { settle: outcome => {
      if (this.writes.get(key) !== token) return;
      this.writes.delete(key);
      if (outcome === 'uncertain') this.markUncertain(key);
      else { this.unknown.delete(key); this.publish(); }
    } };
  }

  markUncertain(key: string) {
    if (this.unknown.has(key)) { this.publish(); return; }
    this.unknown.add(key);
    this.publish({ uncertaintyRevision: this.state.uncertaintyRevision + 1 });
  }

  beginDelete(): boolean {
    if (this.state.exclusive) return false;
    this.publish({ exclusive: true }); return true;
  }
  cancelDelete() { this.publish({ exclusive: false }); }
  canSubmitDelete() { return this.state.exclusive && this.writes.size === 0; }
}
