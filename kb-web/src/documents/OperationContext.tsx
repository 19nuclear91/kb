import { createContext, useContext, useSyncExternalStore } from 'react';
import { DocumentOperations, idleOperations } from './operations';

export const DocumentOperationContext = createContext<DocumentOperations | null>(null);
const subscribeIdle = () => () => {};
const snapshotIdle = () => idleOperations;

export function useDocumentOperations() {
  const operations = useContext(DocumentOperationContext);
  const state = useSyncExternalStore(operations?.subscribe ?? subscribeIdle, operations?.getSnapshot ?? snapshotIdle);
  return { operations, state };
}
