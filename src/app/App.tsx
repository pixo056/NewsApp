import { lazy, Suspense, useEffect, useState } from 'react';
import { NavLink, Link, Navigate, Route, Routes, useLocation, useNavigate } from 'react-router-dom';
import {
  Activity,
  ArrowUpRight,
  Bell,
  Bookmark,
  ChevronRight,
  CircleHelp,
  FileText,
  Home,
  LogIn,
  Mail,
  Menu,
  PenLine,
  Search,
  Settings,
  ShieldCheck,
  Users,
  X,
} from 'lucide-react';
import { useApp } from './context';
import { Avatar, EmptyState, ErrorState, Loading, Verified } from '../components/ui';
import {
  FeedPage,
  CommunitiesPage,
  CommunityPage,
  ArticlePage,
  SavedPage,
  SearchPage,
  CreateCommunityPage,
} from '../pages/ReaderPages';
import {
  ComposePage,
  MyPostsPage,
  ProfilePage,
  SettingsPage,
  AuthPage,
  ReportPage,
  AboutPage,
  NotFoundPage,
  MailboxPage,
  NotificationsPage,
} from '../pages/WorkspacePages';

const CmsApp = lazy(() => import('../cms/CmsApp'));

const navItems = [
  { to: '/', label: 'Лента новостей', icon: Home, end: true },
  { to: '/communities', label: 'Паблики', icon: Users },
  { to: '/saved', label: 'Сохранённое', icon: Bookmark },
  { to: '/my-posts', label: 'Мои публикации', icon: FileText },
];
function Sidebar({ close }: { close: () => void }) {
  const { user, communities, followed, localMailbox } = useApp();
  return (
    <>
      <div className="sidebar-label">МОЙ КАМПУС</div>
      <nav className="main-nav" aria-label="Главное меню">
        {navItems.map(({ to, label, icon: Icon, end }) => (
          <NavLink key={to} to={to} end={end} onClick={close}>
            <Icon size={20} strokeWidth={1.8} />
            {label}
          </NavLink>
        ))}
      </nav>
      <Link to="/create" className="primary-button sidebar-create" onClick={close}>
        <PenLine size={18} />
        Предложить новость
      </Link>
      <div className="sidebar-rule" />
      <div className="sidebar-label">МОИ ПОДПИСКИ</div>
      <div className="sidebar-subscriptions">
        {communities
          .filter((c) => followed.includes(c.id))
          .map((c) => (
            <Link key={c.id} to={`/communities/${c.id}`} onClick={close}>
              <Avatar community={c} size="small" />
              <span>{c.name}</span>
            </Link>
          ))}
        {!followed.length && <p className="field-note">Подпишитесь на интересные паблики</p>}
      </div>
      <Link className="sidebar-explore" to="/communities" onClick={close}>
        Найти интересное <ChevronRight size={15} />
      </Link>
      <div className="sidebar-rule" />
      <nav className="main-nav secondary-nav" aria-label="Настройки и модерация">
        {user?.canModerate && (
          <NavLink to="/cms" onClick={close}>
            <ShieldCheck size={20} />
            Управление сайтом
          </NavLink>
        )}
        <NavLink to="/settings" onClick={close}>
          <Settings size={20} />
          Оформление
        </NavLink>
        {localMailbox && (
          <NavLink to="/dev/mailbox" onClick={close}>
            <Mail size={20} />
            Тестовая почта
          </NavLink>
        )}
      </nav>
      <div className="sidebar-bottom">
        <Link to="/about">О проекте</Link>
        <span>Создано для жизни в кампусе</span>
        <small>УУНиТ · 2026</small>
      </div>
    </>
  );
}
function RightRail() {
  const { communities, followed, toggleFollow, posts } = useApp();
  return (
    <aside className="right-rail" aria-label="Новости и рекомендации">
      <section className="rail-section">
        <div className="rail-heading">
          <h2>Сейчас в кампусе</h2>
          <Activity size={17} />
        </div>
        <p className="rail-caption">Новые истории университетской жизни</p>
        {posts.slice(0, 2).map((p) => (
          <Link to={`/news/${p.id}`} key={p.id} className="event">
            <span className="event-date">
              <b>{new Date(p.publishedAt).getDate()}</b>
              {new Date(p.publishedAt).toLocaleDateString('ru', { month: 'short' })}
            </span>
            <span>
              <strong>{p.title}</strong>
              <small>{p.category}</small>
            </span>
            <ArrowUpRight size={15} />
          </Link>
        ))}
      </section>
      <section className="rail-section">
        <div className="rail-heading">
          <h2>Найдите своих</h2>
          <Users size={17} />
        </div>
        <p className="rail-caption">Сообщества по вашим интересам</p>
        {communities
          .filter((c) => !followed.includes(c.id))
          .slice(0, 3)
          .map((c) => (
            <div className="recommended" key={c.id}>
              <Link to={`/communities/${c.id}`}>
                <Avatar community={c} />
                <span>
                  <strong>
                    {c.name}
                    {c.verified && <Verified />}
                  </strong>
                  <small>{c.members} подписчиков</small>
                </span>
              </Link>
              <button
                className="follow-icon"
                onClick={() => void toggleFollow(c.id)}
                aria-label={`Подписаться на ${c.name}`}
              >
                +
              </button>
            </div>
          ))}
        <Link to="/communities" className="text-link all-communities">
          Все паблики <ArrowUpRight size={15} />
        </Link>
      </section>
      <section className="pitch-card">
        <span className="pitch-icon">
          <PenLine size={20} />
        </span>
        <h2>Есть чем поделиться?</h2>
        <p>Расскажите о том, что происходит рядом. Вашу новость увидит весь кампус.</p>
        <Link to="/create">
          Предложить новость <ArrowUpRight size={17} />
        </Link>
      </section>
      <div className="rail-footer">
        <Link to="/about">О платформе</Link>
        <Link to="/about#rules">Правила сообщества</Link>
        <span>Пульс УУНиТ © 2026</span>
      </div>
    </aside>
  );
}
export function App() {
  const { toast, notify, user, notifications, error, refresh, loading } = useApp();
  const location = useLocation();
  const navigate = useNavigate();
  const [mobileMenu, setMobileMenu] = useState(false);
  const [search, setSearch] = useState('');
  useEffect(() => {
    window.scrollTo(0, 0);
    setMobileMenu(false);
  }, [location.pathname]);
  useEffect(() => {
    if (!toast) return;
    const timer = window.setTimeout(() => notify(''), 6000);
    return () => clearTimeout(timer);
  }, [toast, notify]);
  const wide =
    [
      '/create',
      '/admin',
      '/settings',
      '/profile',
      '/about',
      '/dev/mailbox',
      '/notifications',
      '/communities/new',
    ].includes(location.pathname) ||
    location.pathname.startsWith('/edit/') ||
    /^\/communities\/[^/]+\/submit$/.test(location.pathname) ||
    location.pathname.startsWith('/auth/');
  if (/^\/cms(?:\/|$)/.test(location.pathname)) {
    if (loading) return <Loading />;
    if (error)
      return (
        <main className="cms-entry">
          <ErrorState message={error} retry={() => void refresh()} />
          <Link className="secondary-button" to="/">
            Вернуться в ленту
          </Link>
        </main>
      );
    if (!user) return <Navigate to="/auth/login?returnTo=/cms" replace />;
    if (!user.canModerate)
      return (
        <main className="cms-entry">
          <EmptyState
            title="Управление доступно редакции"
            text="Панелью пользуются администрация платформы и владельцы пабликов. Свои новости можно редактировать в разделе «Мои публикации»."
            action={
              <Link className="primary-button" to="/my-posts">
                Мои публикации
              </Link>
            }
          />
        </main>
      );
    return (
      <Suspense fallback={<Loading />}>
        <Routes>
          <Route path="/cms/*" element={<CmsApp key={user.id} />} />
        </Routes>
      </Suspense>
    );
  }
  return (
    <>
      <a className="skip-link" href="#main">
        К содержимому
      </a>
      <header className="topbar">
        <div className="topbar-inner">
          <button
            className="icon-button menu-toggle"
            aria-label={mobileMenu ? 'Закрыть меню' : 'Открыть меню'}
            aria-expanded={mobileMenu}
            onClick={() => setMobileMenu(!mobileMenu)}
          >
            {mobileMenu ? <X /> : <Menu />}
          </button>
          <Link className="brand" to="/" aria-label="Пульс — главная">
            <span className="brand-mark">
              <Activity strokeWidth={2.3} />
            </span>
            <span>
              пульс<span className="brand-dot">.</span>
            </span>
            <span className="brand-university">УУНиТ</span>
          </Link>
          <form
            className="header-search"
            onSubmit={(e) => {
              e.preventDefault();
              navigate(`/search?q=${encodeURIComponent(search.trim())}`);
            }}
          >
            <Search size={18} />
            <input
              value={search}
              onChange={(e) => setSearch(e.target.value)}
              placeholder="Найти новость или паблик"
              aria-label="Поиск новостей и пабликов"
              maxLength={200}
            />
            <kbd>↵</kbd>
          </form>
          <div className="header-actions">
            {user ? (
              <>
                <Link
                  className="icon-button notification-bell"
                  to="/notifications"
                  aria-label="Уведомления"
                >
                  <Bell size={20} />
                  {notifications.some((n) => !n.read) && <i />}
                </Link>
                <Link to="/profile" className="profile-avatar" aria-label={`Профиль: ${user.name}`}>
                  {user.name.slice(0, 1).toUpperCase()}
                </Link>
              </>
            ) : (
              <Link to="/auth/login" className="login-link">
                <LogIn size={17} />
                Войти
              </Link>
            )}
          </div>
        </div>
      </header>
      <div className="prototype-strip">
        <span>Университетская жизнь — в одной ленте.</span>
        <span>
          Локальная версия <span className="strip-dot">·</span>
          <Link to="/about">
            О проекте <ArrowUpRight size={12} />
          </Link>
        </span>
      </div>
      <div className={`app-grid ${wide ? 'wide' : ''}`}>
        <aside className={`sidebar ${mobileMenu ? 'open' : ''}`}>
          <Sidebar close={() => setMobileMenu(false)} />
        </aside>
        {mobileMenu && (
          <button
            className="menu-overlay"
            aria-label="Закрыть меню"
            onClick={() => setMobileMenu(false)}
          />
        )}
        <main id="main" tabIndex={-1}>
          {error && <ErrorState message={error} retry={() => void refresh()} />}
          <Routes>
            <Route path="/" element={<FeedPage />} />
            <Route path="/subscriptions" element={<FeedPage subscriptions />} />
            <Route path="/communities" element={<CommunitiesPage />} />
            <Route path="/communities/new" element={<CreateCommunityPage />} />
            <Route path="/communities/:id" element={<CommunityPage />} />
            <Route path="/communities/:communityId/submit" element={<ComposePage />} />
            <Route path="/news/:id" element={<ArticlePage />} />
            <Route path="/news/:id/report" element={<ReportPage />} />
            <Route path="/saved" element={<SavedPage />} />
            <Route path="/search" element={<SearchPage />} />
            <Route path="/create" element={<ComposePage />} />
            <Route path="/edit/:id" element={<ComposePage />} />
            <Route path="/my-posts" element={<MyPostsPage />} />
            <Route path="/profile" element={<ProfilePage />} />
            <Route path="/notifications" element={<NotificationsPage />} />
            <Route path="/settings" element={<SettingsPage />} />
            <Route path="/auth/:mode" element={<AuthPage />} />
            <Route path="/admin" element={<Navigate to="/cms" replace />} />
            <Route path="/dev/mailbox" element={<MailboxPage />} />
            <Route path="/about" element={<AboutPage />} />
            <Route path="*" element={<NotFoundPage />} />
          </Routes>
        </main>
        {!wide && <RightRail />}
      </div>
      <nav className="mobile-bottom-nav" aria-label="Быстрая навигация">
        <NavLink to="/" end>
          <Home size={21} />
          <span>Лента</span>
        </NavLink>
        <NavLink to="/communities">
          <Users size={21} />
          <span>Паблики</span>
        </NavLink>
        <NavLink to="/create" className="mobile-compose">
          <PenLine size={22} />
          <span>Создать</span>
        </NavLink>
        <NavLink to="/saved">
          <Bookmark size={21} />
          <span>Сохранено</span>
        </NavLink>
        <NavLink to="/profile">
          <FileText size={21} />
          <span>Профиль</span>
        </NavLink>
      </nav>
      {toast && (
        <div className="toast" role="status">
          <CircleHelp size={20} />
          <span>{toast}</span>
          <button
            className="icon-button"
            onClick={() => notify('')}
            aria-label="Закрыть уведомление"
          >
            <X size={16} />
          </button>
        </div>
      )}
    </>
  );
}
