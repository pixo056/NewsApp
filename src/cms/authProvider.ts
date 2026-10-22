import type { AuthProvider } from 'react-admin';
import { api, ApiError, json } from '../api/client';
import type { User } from '../domain/types';

export const cmsLoginUrl = '/auth/login?returnTo=/cms';

async function moderator() {
  const session = await api<{ user?: User }>('/auth/me');
  if (!session.user) throw { redirectTo: cmsLoginUrl, logoutUser: false };
  if (!session.user.canModerate) throw { redirectTo: '/', logoutUser: false };
  return session.user;
}

export function createCmsAuthProvider(onLogout: () => Promise<void>): AuthProvider {
  return {
    login: async () => {
      window.location.assign(cmsLoginUrl);
    },
    logout: async () => {
      await json('/auth/logout', {});
      await onLogout();
      return cmsLoginUrl;
    },
    checkAuth: async () => {
      await moderator();
    },
    checkError: async (error: unknown) => {
      if (error instanceof ApiError && error.status === 401)
        throw { redirectTo: cmsLoginUrl, logoutUser: false };
    },
    getIdentity: async () => {
      const user = await moderator();
      return { id: user.id, fullName: user.name };
    },
    getPermissions: async () => (await moderator()).role,
    canAccess: async ({ resource }) => {
      const user = await moderator();
      return !['users', 'audit'].includes(resource || '') || user.role === 'ADMIN';
    },
  };
}
