import { createContext, useContext } from 'react';
import type { Community, Notification, Post, User } from '../domain/types';
export type Theme = 'light' | 'dark' | 'system';
export interface State {
  saved: string[];
  followed: string[];
  theme: Theme;
  resolvedTheme: 'light' | 'dark';
  recommended: Community[];
  toast: string;
  user: User | null;
  communities: Community[];
  posts: Post[];
  notifications: Notification[];
  localMailbox: boolean;
  loading: boolean;
  error: string;
  toggleSave: (id: string) => Promise<void>;
  toggleFollow: (id: string) => Promise<void>;
  setTheme: (theme: Theme) => void;
  notify: (message: string) => void;
  refresh: () => Promise<void>;
  logout: () => Promise<void>;
}
export const Context = createContext<State | null>(null);
export function useApp() {
  const value = useContext(Context);
  if (!value) throw new Error('AppProvider required');
  return value;
}
