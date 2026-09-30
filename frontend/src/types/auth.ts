/** Roles as defined by the Spring Boot backend authorisation model. */
export const ROLES = ['VIEWER', 'ANALYST', 'ADMIN'] as const

export type Role = (typeof ROLES)[number]

export const ROLE_RANK: Record<Role, number> = {
  VIEWER: 1,
  ANALYST: 2,
  ADMIN: 3,
}

export function isRole(value: unknown): value is Role {
  return typeof value === 'string' && (ROLES as readonly string[]).includes(value)
}

/** True when `role` is at least as privileged as `minimum`. */
export function hasAtLeast(role: Role, minimum: Role): boolean {
  return ROLE_RANK[role] >= ROLE_RANK[minimum]
}

export const ROLE_LABELS: Record<Role, string> = {
  VIEWER: 'Viewer',
  ANALYST: 'Analyst',
  ADMIN: 'Admin',
}
