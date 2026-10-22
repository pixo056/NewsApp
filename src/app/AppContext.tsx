import { useCallback, useEffect, useState } from 'react';
import type { ReactNode } from 'react';
import type { Community, Notification, Page, Post, User } from '../domain/types';
import { api, json } from '../api/client';
import { Context } from './context';
import type { Theme } from './context';
export function AppProvider({ children }: { children: ReactNode }) {
  const [saved, setSaved] = useState<string[]>([]);
  const [followed, setFollowed] = useState<string[]>([]);
  const [theme, setThemeState] = useState<Theme>(() => {
    const value = localStorage.getItem('newsapp-theme');
    return value === 'dark' || value === 'light' ? value : 'system';
  });
  const [deviceDark, setDeviceDark] = useState(
    () => matchMedia('(prefers-color-scheme: dark)').matches,
  );
  const resolvedTheme = theme === 'system' ? (deviceDark ? 'dark' : 'light') : theme;
  useEffect(() => {
    const media = matchMedia('(prefers-color-scheme: dark)');
    const update = () => setDeviceDark(media.matches);
    update();
    media.addEventListener('change', update);
    return () => media.removeEventListener('change', update);
  }, []);
  useEffect(() => {
    document.documentElement.style.colorScheme = resolvedTheme;
    document.documentElement.style.backgroundColor =
      resolvedTheme === 'dark' ? '#17151e' : '#f7f8fa';
  }, [resolvedTheme]);
  const [toast, notify] = useState('');
  const [user, setUser] = useState<User | null>(null);
  const [communities, setCommunities] = useState<Community[]>([]);
  const [recommended, setRecommended] = useState<Community[]>([]);
  const [posts, setPosts] = useState<Post[]>([]);
  const [notifications, setNotifications] = useState<Notification[]>([]);
  const [localMailbox, setLocalMailbox] = useState(false);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState('');
  const refresh = useCallback(async () => {
    try {
      const session = await api<{ user?: User; localMailbox: boolean }>('/auth/me');
      setUser(session.user || null);
      setLocalMailbox(
        session.localMailbox && ['localhost', '127.0.0.1', '[::1]'].includes(location.hostname),
      );
      const [groups, latest, suggestions] = await Promise.all([
        api<Community[]>('/communities'),
        api<Page<Post>>('/news?limit=3'),
        api<Community[]>('/communities/recommended'),
      ]);
      setCommunities(groups);
      setRecommended(suggestions);
      setPosts(latest.items);
      if (session.user) {
        const [prefs, notices] = await Promise.all([
          api<{ saved: string[]; followed: string[] }>('/me/preferences'),
          api<Notification[]>('/me/notifications'),
        ]);
        setSaved(prefs.saved);
        setFollowed(prefs.followed);
        setNotifications(notices);
      } else {
        setSaved([]);
        setFollowed([]);
        setNotifications([]);
      }
      setError('');
    } catch (e) {
      setError((e as Error).message);
    } finally {
      setLoading(false);
    }
  }, []);
  useEffect(() => {
    void refresh();
  }, [refresh]);
  function setTheme(value: Theme) {
    localStorage.setItem('newsapp-theme', value);
    setThemeState(value);
  }
  async function toggleSave(id: string) {
    if (!user) {
      notify('Войдите в аккаунт, чтобы сохранять новости.');
      return;
    }
    try {
      await api(`/news/${id}/bookmark`, { method: saved.includes(id) ? 'DELETE' : 'PUT' });
      await refresh();
    } catch (e) {
      notify((e as Error).message);
    }
  }
  async function toggleFollow(id: string) {
    if (!user) {
      notify('Войдите в аккаунт, чтобы подписываться на паблики.');
      return;
    }
    try {
      await api(`/communities/${id}/subscription`, {
        method: followed.includes(id) ? 'DELETE' : 'PUT',
      });
      await refresh();
    } catch (e) {
      notify((e as Error).message);
    }
  }
  async function logout() {
    await json('/auth/logout', {});
    await refresh();
    notify('Вы вышли из аккаунта');
  }
  return (
    <Context.Provider
      value={{
        saved,
        followed,
        theme,
        resolvedTheme,
        recommended,
        setTheme,
        toast,
        notify,
        user,
        communities,
        posts,
        notifications,
        localMailbox,
        loading,
        error,
        refresh,
        toggleSave,
        toggleFollow,
        logout,
      }}
    >
      <div data-theme={resolvedTheme}>{children}</div>
    </Context.Provider>
  );
}
