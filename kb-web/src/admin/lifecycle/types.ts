import type { Version, RoleSummary } from '../types';

export type Restriction = 'CLEAR' | 'OFFBOARDED' | 'REVIEW';
export interface LifecycleUser {
  userId: string; version: Version; mappingVersion: Version; manualEnabled: boolean; enabled: boolean;
  kind: 'NONE' | 'MANAGED' | 'EMERGENCY'; restriction: Restriction; connectionId: string | null;
  lastEventId: string | null; updatedAt: string | null; restoredAt: string | null;
}
export interface LifecycleStatus {
  subscriptionEnabled: boolean; configured: boolean; connectionId: string; connected: boolean;
  lastConnectedAt: string | null; emergencyReady: boolean;
  counts: { managed: Version; restricted: Version; unmapped: Version; review: Version; failed: Version };
}
export interface LifecycleEvent {
  id: string; eventType: string; conflicted: boolean; bornAt: string; recordedAt: string;
  restricted: Version; review: Version; unmapped: Version; failed: Version;
}
export interface LifecycleItem {
  id: string; userId: string | null; mappingVersion: Version | null; status: 'OFFBOARDED' | 'REVIEW' | 'UNMAPPED' | 'IGNORED' | 'FAILED';
  errorCode: string | null; attempts: number; updatedAt: string; resolvedAt: string | null;
}
export interface EmergencyUser { userId: string; displayName: string; version: Version; mappingVersion: Version; qualified: boolean }
export interface Proof { proof: string; expiresAt: string; version: Version; mappingVersion: Version }
export interface EmployeeInput { source: 'dingtalk'; connectionId: string; employeeId: string; version: bigint; mappingVersion: bigint }
export interface EmployeeVerified extends Proof { displayName: string; connectionId: string }
export interface RestorationVerified extends Proof {
  employeeName: string; departmentId: string; departmentName: string; clearanceLevel: number; roles: RoleSummary[];
  manualEnabled: boolean; restriction: Restriction; eventBoundary: Version;
}
export interface RestorationResult {
  userId: string; version: Version; mappingVersion: Version; manualEnabled: boolean; enabled: boolean;
  passwordChangeRequired: boolean; outcome: 'RESTORED_RELOGIN_REQUIRED' | 'RESTORED_MANUALLY_DISABLED';
}
export interface MappingResult { userId: string; version: Version; mappingVersion: Version; managed: boolean; manualEnabled: boolean; enabled: boolean; restriction: Restriction }
export interface RetryResult { eventId: string; duplicate: boolean; conflicted: boolean; restricted: number; review: number; unmapped: number }
export interface EmergencyResult { userId: string; mappingVersion: Version; registered: boolean; replacedUserId: string | null }
