import type { Id } from '../types';

/** version 在安全整数内解码为 number，超出时保持 bigint，回传时都按精确整数编码。 */
export type Version = number | bigint;
export interface AdminUser { userId: Id; username: string; displayName: string; departmentId: Id | null; clearanceLevel: number; enabled: boolean; version: Version }
export interface AdminDepartment { departmentId: Id; code: string; name: string; parentId: Id | null; path: string; enabled: boolean; version: Version }
export interface RoleSummary { roleId: Id; code: string; name: string }
export interface RoleDetail extends RoleSummary { permissions: string[] }
export interface PermissionSummary { code: string; description: string }
export interface UserFilters { q?: string; departmentId?: Id; enabled?: boolean }
