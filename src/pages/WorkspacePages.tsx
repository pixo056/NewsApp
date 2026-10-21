import { useEffect, useState } from 'react';
import { Link, useNavigate, useParams, useSearchParams } from 'react-router-dom';
import {
  ArrowRight,
  Bell,
  Check,
  FileText,
  ImagePlus,
  Info,
  LockKeyhole,
  LogOut,
  Mail,
  Monitor,
  Moon,
  Palette,
  PenLine,
  Plus,
  ShieldCheck,
  Smartphone,
  Sun,
  Trash2,
  Video,
  X,
} from 'lucide-react';
import { useApp } from '../app/context';
import { api, json, upload } from '../api/client';
import { useResource, useUnsavedChanges } from '../api/hooks';
import { validateNewsDraft, validateNewsMedia } from '../domain/news-validation';
import { categories } from '../data/categories';
import type { MediaAsset, Post, Report, User } from '../domain/types';
import {
  AccountGate,
  Avatar,
  EmptyState,
  ErrorState,
  Loading,
  PageHeading,
  Status,
  formatDate,
} from '../components/ui';

export function ComposePage() {
  const { id } = useParams();
  const [params] = useSearchParams();
  const { user, communities, notify, refresh } = useApp();
  const navigate = useNavigate();
  const original = useResource<Post>(id && user ? `/me/news/${id}` : null);
  const [title, setTitle] = useState('');
  const [text, setText] = useState('');
  const [category, setCategory] = useState('Университет');
  const [communityId, setCommunity] = useState(params.get('community') || '');
  const [source, setSource] = useState('');
  const [media, setMedia] = useState<MediaAsset[]>([]);
  const [error, setError] = useState('');
  const [busy, setBusy] = useState(false);
  const [uploading, setUploading] = useState(false);
  const [progress, setProgress] = useState(0);
  const [dirty, setDirty] = useState(false);
  const [touched, setTouched] = useState({ title: false, text: false, source: false });
  useUnsavedChanges(dirty);
  useEffect(() => {
    const p = original.data;
    if (!p) return;
    setTitle(p.title);
    setText(p.text);
    setCategory(p.category);
    setCommunity(p.communityId || '');
    setSource(p.source);
    setMedia(p.media);
    setDirty(false);
    setTouched({ title: false, text: false, source: false });
  }, [original.data]);
  useEffect(() => {
    if (!id) {
      setTitle('');
      setText('');
      setCategory('Университет');
      setCommunity(params.get('community') || '');
      setSource('');
      setMedia([]);
      setDirty(false);
      setTouched({ title: false, text: false, source: false });
    }
  }, [id]);
  const selected = communities.find((c) => c.id === communityId);
  const video = media.some((m) => m.kind === 'video');
  const fieldErrors = validateNewsDraft({ title, text, source, hasVideo: video });
  async function choose(files: FileList | null) {
    if (!files) return;
    const chosen = [...files];
    if (!chosen.length) return;
    const mediaError = validateNewsMedia(chosen, media);
    if (mediaError) {
      setError(mediaError);
      return;
    }
    setUploading(true);
    setError('');
    try {
      for (const file of chosen) {
        setProgress(0);
        const asset = await upload(file, setProgress);
        setMedia((old) => [...old, asset]);
        setDirty(true);
      }
    } catch (e) {
      setError((e as Error).message);
    } finally {
      setUploading(false);
    }
  }
  return (
    <AccountGate>
      <PageHeading
        title={id ? 'Редактирование новости' : 'Что нового в кампусе?'}
        subtitle="Поделитесь тем, что заслуживает внимания."
        back="/my-posts"
      />
      {id && original.loading ? (
        <Loading />
      ) : original.error ? (
        <ErrorState message={original.error} />
      ) : (
        <div className="editor-grid">
          <form
            className="panel editor"
            noValidate
            onChange={() => setDirty(true)}
            onSubmit={async (e) => {
              e.preventDefault();
              setTouched({ title: true, text: true, source: true });
              if (Object.keys(fieldErrors).length) {
                setError('Проверьте выделенные поля перед отправкой.');
                return;
              }
              setBusy(true);
              setError('');
              try {
                const input = {
                  title,
                  text,
                  category,
                  communityId: communityId || null,
                  source: source.trim(),
                  mediaIds: media.map((m) => m.id),
                };
                const result = await json<{ id: string }>(
                  id ? `/news/${id}/revisions` : '/news',
                  input,
                );
                setDirty(false);
                await refresh();
                notify('Новость отправлена на модерацию. Статус доступен в ваших публикациях.');
                navigate('/my-posts', { state: { created: result.id } });
              } catch (err) {
                setError((err as Error).message);
              } finally {
                setBusy(false);
              }
            }}
          >
            <div className="editor-section">
              <h2>Ваша история</h2>
              <label className="form-field">
                Куда отправить новость
                <select
                  value={communityId}
                  disabled={!!id}
                  onChange={(e) => setCommunity(e.target.value)}
                >
                  <option value="">Личная публикация · проверяет администрация</option>
                  {communities.map((c) => (
                    <option key={c.id} value={c.id}>
                      {c.name}
                      {c.verified ? ' · УУНиТ' : ''}
                    </option>
                  ))}
                </select>
              </label>
              {selected && (
                <p className="field-note">
                  {selected.verified
                    ? 'Новость проверит администрация платформы.'
                    : 'Новость проверит создатель этого паблика.'}{' '}
                  Автор заявки: {user?.name}.
                </p>
              )}
              <label className="form-field">
                Категория
                <select value={category} onChange={(e) => setCategory(e.target.value)}>
                  {categories
                    .filter((c) => c !== 'Все')
                    .map((c) => (
                      <option key={c}>{c}</option>
                    ))}
                </select>
              </label>
              <label className="form-field">
                Заголовок *
                <input
                  required
                  placeholder="Самое важное — в одной строке"
                  value={title}
                  onChange={(e) => setTitle(e.target.value)}
                  onBlur={() => setTouched((old) => ({ ...old, title: true }))}
                  maxLength={300}
                  aria-invalid={touched.title && !!fieldErrors.title}
                  aria-describedby="news-title-count news-title-error"
                />
                <span className="field-counter" id="news-title-count">
                  {Array.from(title).length} / 150 · минимум 10
                </span>
                {touched.title && fieldErrors.title && (
                  <span className="form-error" id="news-title-error" role="alert">
                    {fieldErrors.title}
                  </span>
                )}
              </label>
              <label className="form-field">
                Текст новости
                <textarea
                  rows={8}
                  placeholder="Что произошло? Где, когда и почему это интересно?"
                  value={text}
                  maxLength={20000}
                  onChange={(e) => setText(e.target.value)}
                  onBlur={() => setTouched((old) => ({ ...old, text: true }))}
                  required={!video}
                  aria-invalid={touched.text && !!fieldErrors.text}
                  aria-describedby="news-text-count news-text-error"
                />
                <span className="field-counter" id="news-text-count">
                  {Array.from(text).length.toLocaleString('ru')} / 10 000 · минимум 50 без видео
                </span>
                {touched.text && fieldErrors.text && (
                  <span className="form-error" id="news-text-error" role="alert">
                    {fieldErrors.text}
                  </span>
                )}
              </label>
            </div>
            <div className="editor-section">
              <h2>Добавьте настроение</h2>
              <p className="muted">
                До 4 фото по 10 МиБ либо одно видео до 200 МиБ, 3 минут и 1080p.
              </p>
              <div className="attachment-grid">
                {media.map((m) => (
                  <div key={m.id} className="attachment">
                    {m.kind === 'image' ? (
                      <img src={m.url} alt="Прикреплённая фотография" />
                    ) : (
                      <video controls preload="none" poster={m.posterUrl} src={m.url} />
                    )}
                    <button
                      type="button"
                      className="icon-button"
                      aria-label="Убрать вложение"
                      onClick={() => {
                        setMedia(media.filter((item) => item.id !== m.id));
                        setDirty(true);
                        if (!original.data?.media.some((asset) => asset.id === m.id))
                          void api(`/media/${m.id}`, { method: 'DELETE' }).catch(() => {});
                      }}
                    >
                      <X size={17} />
                    </button>
                  </div>
                ))}
              </div>
              <label className={`upload-zone ${uploading ? 'disabled' : ''}`}>
                <span>
                  <ImagePlus size={27} />
                  <Video size={25} />
                </span>
                <strong>
                  {uploading
                    ? progress === 100
                      ? 'Обрабатываем файл на сервере…'
                      : `Загрузка: ${progress}%`
                    : 'Выберите фото или видео'}
                </strong>
                <small>JPG, PNG, WebP · MP4, MOV</small>
                <input
                  type="file"
                  multiple
                  disabled={uploading || busy}
                  aria-label="Загрузить фото или видео"
                  accept="image/jpeg,image/png,image/webp,video/mp4,video/quicktime,.mov"
                  onChange={(e) => {
                    void choose(e.target.files);
                    e.target.value = '';
                  }}
                />
              </label>
              {uploading && <progress className="upload-progress" max={100} value={progress} />}
            </div>
            <div className="editor-section">
              <label className="form-field">
                Ссылка на источник
                <input
                  type="url"
                  value={source}
                  maxLength={2000}
                  onChange={(e) => setSource(e.target.value)}
                  onBlur={() => setTouched((old) => ({ ...old, source: true }))}
                  aria-invalid={touched.source && !!fieldErrors.source}
                  aria-describedby="news-source-error"
                  placeholder="https://…"
                />
                {touched.source && fieldErrors.source && (
                  <span className="form-error" id="news-source-error" role="alert">
                    {fieldErrors.source}
                  </span>
                )}
              </label>
              <p className="field-note">Укажите источник, если используете чужие материалы.</p>
            </div>
            {error && (
              <p className="form-error" role="alert">
                {error}
              </p>
            )}
            {!user?.verified && (
              <div className="editor-section">
                <div className="info-callout">
                  <Mail size={18} />
                  <span>
                    Сначала подтвердите почту.{' '}
                    <Link to="/profile" className="text-link">
                      Открыть профиль
                    </Link>
                  </span>
                </div>
              </div>
            )}
            <div className="editor-footer">
              <span>
                <ShieldCheck size={18} />
                Каждая новость проходит модерацию
              </span>
              <button className="primary-button" disabled={busy || uploading || !user?.verified}>
                {busy ? 'Отправляем…' : 'Отправить на модерацию'}
                <ArrowRight size={17} />
              </button>
            </div>
          </form>
          <aside className="editor-help">
            <div className="panel help-card">
              <span className="help-icon">
                <PenLine size={24} />
              </span>
              <h2>
                Хорошая новость
                <br />
                начинается с вас
              </h2>
              <p>Пишите понятно и по делу. Проверьте имена, даты и факты перед отправкой.</p>
              <ul>
                <li>Заголовок: 10–150 символов</li>
                <li>Текст: 50–10 000 символов</li>
                <li>С видео текст необязателен</li>
                <li>До 5 заявок в сутки на аккаунт</li>
              </ul>
              <div className="info-callout">
                <Info size={18} />
                <span>
                  Пока правки проходят проверку, читатели видят прежнюю одобренную версию.
                </span>
              </div>
            </div>
          </aside>
        </div>
      )}
    </AccountGate>
  );
}
export function MyPostsPage() {
  const { user, notify, refresh } = useApp();
  const resource = useResource<Post[]>(user ? '/me/news' : null);
  const [filter, setFilter] = useState('all');
  const posts = resource.data || [];
  return (
    <AccountGate>
      <PageHeading
        title="Мои публикации"
        subtitle="От первой идеи до новости в общей ленте."
        action={
          <Link to="/create" className="primary-button">
            <Plus size={18} />
            Новая
          </Link>
        }
      />
      <div className="stats-row">
        <div>
          <strong>{posts.length}</strong>
          <span>всего новостей</span>
        </div>
        <div>
          <strong>{posts.filter((p) => p.status === 'pending').length}</strong>
          <span>на модерации</span>
        </div>
        <div>
          <strong>{posts.filter((p) => p.status === 'published').length}</strong>
          <span>опубликованы</span>
        </div>
      </div>
      <div className="filter-tabs">
        {[
          ['all', 'Все'],
          ['pending', 'На модерации'],
          ['published', 'Опубликованы'],
          ['rejected', 'Отклонены'],
        ].map(([value, label]) => (
          <button
            key={value}
            className={filter === value ? 'active' : ''}
            onClick={() => setFilter(value)}
          >
            {label}
          </button>
        ))}
      </div>
      {resource.loading && <Loading />}
      {resource.error && <ErrorState message={resource.error} retry={resource.reload} />}
      <div className="own-posts">
        {posts
          .filter((p) => filter === 'all' || p.status === filter)
          .map((p) => (
            <article key={p.id} className="panel own-post">
              <div>
                <Status status={p.status} />
                <span className="post-date">{p.category}</span>
              </div>
              {p.hasPendingChanges && (
                <span className="field-note">
                  Прежняя версия опубликована · изменения на модерации
                </span>
              )}
              <h2>{p.title}</h2>
              <p>{p.text}</p>
              {p.rejectionReason && (
                <div className="rejection-reason">
                  <Info size={17} />
                  <span>
                    <b>Комментарий модератора</b>
                    {p.rejectionReason}
                  </span>
                </div>
              )}
              <div className="own-post-actions">
                <Link to={`/edit/${p.id}`} className="text-link">
                  <PenLine size={15} />
                  Редактировать
                </Link>
                <Link to={`/news/${p.id}`} className="text-link">
                  Открыть
                </Link>
                <button
                  className="icon-button"
                  aria-label={`Удалить «${p.title}»`}
                  onClick={async () => {
                    if (!window.confirm('Удалить публикацию? Она исчезнет из ленты.')) return;
                    try {
                      await api(`/news/${p.id}`, { method: 'DELETE' });
                      resource.reload();
                      await refresh();
                      notify('Публикация удалена');
                    } catch (e) {
                      notify((e as Error).message);
                    }
                  }}
                >
                  <Trash2 size={17} />
                </button>
              </div>
            </article>
          ))}
      </div>
      {!resource.loading && !posts.length && (
        <EmptyState
          title="Начните свою историю"
          text="Ваши новости и их статусы появятся здесь."
          action={
            <Link className="primary-button" to="/create">
              Предложить новость
            </Link>
          }
        />
      )}
    </AccountGate>
  );
}
export function ProfilePage() {
  const { user, saved, followed, communities, notify, refresh, logout, localMailbox } = useApp();
  const [editing, setEditing] = useState(false);
  const [busy, setBusy] = useState(false);
  return (
    <AccountGate>
      {user && (
        <>
          <PageHeading title="Мой профиль" subtitle="Ваше место в университетском сообществе." />
          <section className="panel profile-card">
            <div className="profile-cover">
              <span>ЧАСТЬ БОЛЬШЕГО</span>
              <span>✳</span>
            </div>
            <div className="profile-body">
              <span className="profile-avatar big">{user.name.slice(0, 1)}</span>
              <span className="demo-badge">
                {user.role === 'ADMIN' ? 'Администратор платформы' : 'Участник сообщества'}
              </span>
              <h1>{user.name}</h1>
              <p className="muted">
                @{user.username} · {user.email}
              </p>
              <p>{user.bio || 'Каждая история начинается с человека.'}</p>
              <div className="profile-stats">
                <Link to="/my-posts">
                  <b>
                    <FileText size={24} />
                  </b>
                  публикации
                </Link>
                <Link to="/subscriptions">
                  <b>{followed.length}</b>подписки
                </Link>
                <Link to="/saved">
                  <b>{saved.length}</b>сохранено
                </Link>
              </div>
              <div className="info-callout">
                {user.verified ? (
                  <>
                    <Check size={18} />
                    <span>
                      Почта подтверждена. Вы можете предлагать новости и создавать паблики.
                    </span>
                  </>
                ) : (
                  <>
                    <Mail size={18} />
                    <span>
                      Подтвердите почту, чтобы публиковать новости.{' '}
                      {localMailbox ? (
                        <Link className="text-link" to="/dev/mailbox">
                          Открыть тестовую почту
                        </Link>
                      ) : (
                        'В тестовом режиме попросите владельца ноутбука подтвердить адрес через локальную почту.'
                      )}
                    </span>
                  </>
                )}
              </div>
              <div className="profile-actions">
                {!user.verified && (
                  <button
                    className="secondary-button"
                    disabled={busy}
                    onClick={async () => {
                      setBusy(true);
                      try {
                        const result = await json<{ message: string }>(
                          '/auth/resend-verification',
                          {},
                        );
                        notify(result.message);
                      } catch (e) {
                        notify((e as Error).message);
                      } finally {
                        setBusy(false);
                      }
                    }}
                  >
                    Отправить письмо ещё раз
                  </button>
                )}
                <button className="secondary-button" onClick={() => setEditing(!editing)}>
                  <PenLine size={16} />
                  Изменить профиль
                </button>
                <Link className="secondary-button" to="/auth/reset">
                  Сменить пароль
                </Link>
                <button
                  className="secondary-button"
                  onClick={() => void logout().catch((e) => notify(e.message))}
                >
                  <LogOut size={16} />
                  Выйти
                </button>
              </div>
              {editing && (
                <form
                  className="community-edit"
                  onSubmit={async (e) => {
                    e.preventDefault();
                    const fields = new FormData(e.currentTarget);
                    try {
                      await json<User>('/me', Object.fromEntries(fields), 'PATCH');
                      await refresh();
                      setEditing(false);
                      notify('Профиль сохранён');
                    } catch (err) {
                      notify((err as Error).message);
                    }
                  }}
                >
                  <label className="form-field">
                    Имя
                    <input name="name" defaultValue={user.name} required maxLength={80} />
                  </label>
                  <label className="form-field">
                    О себе
                    <textarea name="bio" defaultValue={user.bio} rows={3} maxLength={500} />
                  </label>
                  <button className="primary-button">Сохранить</button>
                </form>
              )}
            </div>
          </section>
          <h2 className="section-title">Мои паблики</h2>
          <div className="panel search-communities">
            {communities
              .filter((c) => c.ownerId === user.id)
              .map((c) => (
                <Link key={c.id} to={`/communities/${c.id}`}>
                  <Avatar community={c} />
                  <span>{c.name}</span>
                  <ArrowRight size={16} />
                </Link>
              ))}
            <Link to="/communities/new">
              <Plus size={20} />
              Создать паблик
            </Link>
          </div>
          {user.canModerate && (
            <Link className="primary-button profile-moderation" to="/cms">
              <ShieldCheck size={18} />
              Управление сайтом
            </Link>
          )}
        </>
      )}
    </AccountGate>
  );
}
export function SettingsPage() {
  const { theme, setTheme } = useApp();
  return (
    <>
      <PageHeading title="Под ваше настроение" subtitle="Знакомая лента. Немного больше вас." />
      <section className="panel settings-panel">
        <h2>Оформление</h2>
        <p className="muted">Выберите тему. Ваш выбор сохраняется на этом устройстве.</p>
        <div className="theme-options">
          {[
            { id: 'light' as const, label: 'Светлая', icon: Sun },
            { id: 'dark' as const, label: 'Тёмная', icon: Moon },
            { id: 'lavender' as const, label: 'Лавандовая', icon: Palette },
          ].map(({ id, label, icon: Icon }) => (
            <button
              className={`theme-option ${id} ${theme === id ? 'selected' : ''}`}
              key={id}
              onClick={() => setTheme(id)}
              aria-pressed={theme === id}
            >
              <span className="theme-preview">
                <i />
                <span>
                  <i />
                  <i />
                  <i />
                </span>
              </span>
              <span>
                <Icon size={17} />
                {label}
                {theme === id && <Check size={18} />}
              </span>
            </button>
          ))}
        </div>
      </section>
      <section className="panel settings-panel">
        <h2>Один кампус. Любой экран.</h2>
        <div className="platform-info">
          <Monitor />
          <span>
            <b>На компьютере</b>Лента, паблики и кабинет модератора
          </span>
        </div>
        <div className="platform-info">
          <Smartphone />
          <span>
            <b>На телефоне</b>Адаптивный интерфейс и нижняя навигация
          </span>
        </div>
        <p className="muted">
          Для установки web-приложения используйте пункт «Установить приложение» или «На экран
          Домой» в меню вашего браузера. При отсутствии связи доступен экран с повторным
          подключением.
        </p>
      </section>
    </>
  );
}
export function AuthPage() {
  const { mode } = useParams();
  const [params] = useSearchParams();
  const token = params.get('token');
  const { refresh, user, localMailbox } = useApp();
  const navigate = useNavigate();
  const [error, setError] = useState('');
  const [message, setMessage] = useState('');
  const [busy, setBusy] = useState(false);
  useEffect(() => {
    setError('');
    setMessage('');
  }, [mode, token]);
  if (!['login', 'register', 'reset', 'verify'].includes(mode || '')) return <NotFoundPage />;
  const register = mode === 'register',
    reset = mode === 'reset',
    verify = mode === 'verify';
  return (
    <div className="auth-layout">
      <div className="auth-story">
        <span className="eyebrow">ВАШ ГОЛОС В КАМПУСЕ</span>
        <h1>
          События.
          <br />
          Люди.
          <br />
          <span>Возможности.</span>
        </h1>
        <p>
          Читайте без регистрации.
          <br />
          Войдите, чтобы стать частью истории.
        </p>
        <span className="auth-star" aria-hidden="true">
          ✳
        </span>
      </div>
      <form
        key={`${mode}-${token || ''}`}
        className="panel auth-form"
        onSubmit={async (e) => {
          e.preventDefault();
          const fields = new FormData(e.currentTarget);
          setBusy(true);
          setError('');
          setMessage('');
          try {
            if (mode === 'login') {
              const body = new URLSearchParams({
                username: String(fields.get('username')).trim().toLowerCase(),
                password: String(fields.get('password')),
              });
              await api('/auth/login', {
                method: 'POST',
                headers: { 'Content-Type': 'application/x-www-form-urlencoded' },
                body,
              });
              await refresh();
              navigate(params.get('returnTo') === '/cms' ? '/cms' : '/profile');
            } else if (register) {
              const result = await json<{ message: string }>(
                '/auth/register',
                Object.fromEntries(fields),
              );
              setMessage(result.message);
            } else if (verify) {
              const result = await json<{ message: string }>('/auth/verify-email', { token });
              setMessage(result.message);
              await refresh();
            } else {
              const result = await json<{ message: string }>(
                token ? '/auth/reset-password' : '/auth/forgot-password',
                token
                  ? { token, password: fields.get('password') }
                  : { email: fields.get('email') },
              );
              setMessage(result.message);
              if (token) await refresh();
            }
          } catch (err) {
            setError((err as Error).message);
          } finally {
            setBusy(false);
          }
        }}
      >
        <span className="auth-lock">
          <LockKeyhole size={24} />
        </span>
        <h2>
          {verify
            ? 'Подтвердите почту'
            : reset
              ? token
                ? 'Новый пароль'
                : 'Восстановить доступ'
              : register
                ? 'Присоединяйтесь'
                : 'С возвращением'}
        </h2>
        <p className="muted">
          {verify
            ? 'Нажмите кнопку, чтобы подтвердить адрес из письма.'
            : reset
              ? 'Ссылка восстановления действует 30 минут.'
              : register
                ? 'Всё интересное начинается с знакомства.'
                : 'Ваша университетская жизнь — здесь.'}
        </p>
        {register && (
          <label className="form-field">
            Имя
            <input
              name="name"
              autoComplete="name"
              required
              maxLength={80}
              placeholder="Как к вам обращаться"
            />
          </label>
        )}
        {!reset && !verify && (
          <label className="form-field">
            Логин
            <input
              name="username"
              autoComplete="username"
              required
              minLength={3}
              maxLength={32}
              pattern="[a-zA-Z0-9_.\-]+"
              placeholder="student"
            />
          </label>
        )}
        {(register || (reset && !token)) && (
          <label className="form-field">
            Электронная почта
            <input
              name="email"
              type="email"
              autoComplete="email"
              required
              maxLength={254}
              defaultValue={reset ? user?.email : ''}
              placeholder="you@example.com"
            />
          </label>
        )}
        {!verify && (!reset || token) && (
          <label className="form-field">
            Пароль
            <input
              name="password"
              type="password"
              autoComplete={mode === 'login' ? 'current-password' : 'new-password'}
              minLength={mode === 'login' ? undefined : 10}
              maxLength={64}
              required
              placeholder={mode === 'login' ? 'Ваш пароль' : 'Минимум 10 символов'}
            />
          </label>
        )}
        {mode === 'login' && (
          <Link className="text-link forgot-password" to="/auth/reset">
            Забыли пароль?
          </Link>
        )}
        {error && (
          <p className="form-error" role="alert">
            {error}
          </p>
        )}
        {message && (
          <div className="success-callout" role="status">
            <Check size={18} />
            {message}
          </div>
        )}
        <button className="primary-button" disabled={busy || (verify && (!token || !!message))}>
          {busy
            ? 'Подождите…'
            : verify
              ? 'Подтвердить почту'
              : reset
                ? token
                  ? 'Сохранить новый пароль'
                  : 'Отправить письмо'
                : register
                  ? 'Создать аккаунт'
                  : 'Войти'}
          <ArrowRight size={17} />
        </button>
        <p className="auth-switch">
          {register
            ? 'Уже знакомы? '
            : reset || verify
              ? 'Вернуться к входу? '
              : 'Первый раз здесь? '}
          <Link to={mode === 'login' ? '/auth/register' : '/auth/login'}>
            {mode === 'login' ? 'Создать аккаунт' : 'Войти'}
          </Link>
        </p>
        {
          <div className="info-callout">
            <Mail size={18} />
            <span>
              Локальный режим: письма не отправляются наружу.{' '}
              {localMailbox ? (
                <Link className="text-link" to="/dev/mailbox">
                  Открыть тестовую почту
                </Link>
              ) : (
                'Для подтверждения адреса или сброса пароля попросите владельца ноутбука открыть тестовую почту через localhost.'
              )}
            </span>
          </div>
        }
      </form>
    </div>
  );
}
export function MailboxPage() {
  const { localMailbox, loading } = useApp();
  const resource = useResource<
    {
      id: string;
      recipient: string;
      subject: string;
      body: string;
      actionUrl: string;
      createdAt: string;
    }[]
  >(localMailbox ? '/dev/mailbox' : null);
  if (loading) return <Loading />;
  if (!localMailbox) return <NotFoundPage />;
  return (
    <>
      <PageHeading
        title="Тестовая почта"
        subtitle="Локальный ящик для проверки регистрации и восстановления доступа."
        action={
          <button className="secondary-button" onClick={resource.reload}>
            Обновить
          </button>
        }
      />
      <div className="admin-notice">
        <Info size={18} />
        Этот ящик показывает тестовые письма всех локальных аккаунтов. Письма сохраняются только в
        базе на вашем компьютере.
      </div>
      {resource.loading && <Loading />}
      {resource.error && <ErrorState message={resource.error} retry={resource.reload} />}
      <div className="mail-list">
        {resource.data?.map((mail) => (
          <article className="panel mail-card" key={mail.id}>
            <span className="post-date">
              {formatDate(mail.createdAt)} · {mail.recipient}
            </span>
            <h2>{mail.subject}</h2>
            <p>{mail.body}</p>
            <Link className="primary-button" to={mail.actionUrl}>
              Открыть ссылку из письма <ArrowRight size={16} />
            </Link>
          </article>
        ))}
      </div>
      {!resource.loading && !resource.data?.length && (
        <EmptyState
          title="Писем пока нет"
          text="Зарегистрируйтесь или запросите восстановление пароля."
        />
      )}
    </>
  );
}
export function ReportPage() {
  const { id } = useParams();
  const { user, notify } = useApp();
  const post = useResource<Post>(`/news/${id}`);
  const [busy, setBusy] = useState(false);
  const [sent, setSent] = useState(false);
  const [error, setError] = useState('');
  return (
    <AccountGate>
      <PageHeading
        title="Сообщить о проблеме"
        subtitle="Помогите сделать университетское сообщество лучше."
        back={`/news/${id}`}
      />
      {sent ? (
        <EmptyState
          title="Жалоба отправлена"
          text="Модератор рассмотрит её и при необходимости примет меры."
          action={
            <Link className="primary-button" to={`/news/${id}`}>
              Вернуться к новости
            </Link>
          }
        />
      ) : (
        <form
          className="panel report-form"
          onSubmit={async (e) => {
            e.preventDefault();
            setBusy(true);
            const fields = new FormData(e.currentTarget);
            try {
              await json(`/news/${id}/reports`, Object.fromEntries(fields));
              setSent(true);
              notify('Жалоба отправлена модератору');
            } catch (err) {
              setError((err as Error).message);
            } finally {
              setBusy(false);
            }
          }}
        >
          <p className="muted">Новость</p>
          <h2>{post.data?.title || 'Загрузка…'}</h2>
          {post.error && <ErrorState message={post.error} />}
          <fieldset>
            <legend>Что не так с публикацией?</legend>
            {[
              'Недостоверная информация',
              'Спам или реклама',
              'Оскорбления или недопустимый контент',
              'Нарушение авторских прав',
              'Другая причина',
            ].map((reason, i) => (
              <label className="radio-row" key={reason}>
                <input
                  type="radio"
                  name="reason"
                  value={reason}
                  defaultChecked={i === 0}
                  required
                />
                {reason}
              </label>
            ))}
          </fieldset>
          <label className="form-field">
            Комментарий
            <textarea name="comment" placeholder="Расскажите подробнее" rows={4} maxLength={2000} />
          </label>
          {error && (
            <p className="form-error" role="alert">
              {error}
            </p>
          )}
          <button className="primary-button" disabled={busy || !user?.verified}>
            {busy
              ? 'Отправляем…'
              : user?.verified
                ? 'Отправить жалобу'
                : 'Сначала подтвердите почту'}
            <ArrowRight size={16} />
          </button>
        </form>
      )}
    </AccountGate>
  );
}
export function AdminPage() {
  const { user, communities, refresh, notify } = useApp();
  const queue = useResource<Post[]>(user?.canModerate ? '/admin/revisions' : null);
  const reports = useResource<Report[]>(user?.canModerate ? '/admin/reports' : null);
  const audit = useResource<
    { id: string; action: string; detail: string; username: string; createdAt: string }[]
  >(user?.role === 'ADMIN' ? '/admin/audit' : null);
  const [tab, setTab] = useState('news');
  const [selectedId, setSelectedId] = useState('');
  const [reason, setReason] = useState('');
  const [rejecting, setRejecting] = useState(false);
  const [busy, setBusy] = useState(false);
  const selected = queue.data?.find((p) => p.id === selectedId) || queue.data?.[0];
  async function decide(approve: boolean) {
    if (!selected) return;
    setBusy(true);
    try {
      await json(`/admin/revisions/${selected.revisionId}/${approve ? 'approve' : 'reject'}`, {
        reason,
      });
      queue.reload();
      audit.reload();
      setRejecting(false);
      setReason('');
      await refresh();
      notify(approve ? 'Новость опубликована' : 'Новость отклонена. Автор получил уведомление.');
    } catch (e) {
      notify((e as Error).message);
    } finally {
      setBusy(false);
    }
  }
  return (
    <AccountGate>
      {!user?.canModerate ? (
        <EmptyState
          title="Кабинет доступен модераторам"
          text="Создайте обычный паблик, чтобы модерировать его новости. Официальные паблики проверяет администрация платформы."
          action={
            <Link className="primary-button" to="/communities/new">
              Создать паблик
            </Link>
          }
        />
      ) : (
        <>
          <PageHeading
            title="Кабинет модератора"
            subtitle={
              user.role === 'ADMIN'
                ? 'Администрация платформы: все публикации и официальные паблики.'
                : 'Вы проверяете новости своих обычных пабликов.'
            }
            action={
              <span className="moderator-badge">
                <ShieldCheck size={17} />
                {user.name}
              </span>
            }
          />
          <div className="filter-tabs admin-tabs">
            {[
              ['news', 'Новости', queue.data?.length || 0],
              [
                'reports',
                'Жалобы',
                reports.data?.filter((r) => r.status === 'pending').length || 0,
              ],
              ['limits', 'Паблики', 0],
              ...(user.role === 'ADMIN' ? [['audit', 'История', 0]] : []),
            ].map(([key, label, count]) => (
              <button
                key={key}
                className={tab === key ? 'active' : ''}
                onClick={() => setTab(String(key))}
              >
                {label}
                {!!count && <span>{count}</span>}
              </button>
            ))}
          </div>
          {tab === 'news' && (
            <>
              {queue.loading && <Loading />}
              {queue.error && <ErrorState message={queue.error} retry={queue.reload} />}
              {selected ? (
                <div className="moderation-grid">
                  <section className="panel moderation-list">
                    <h2>Ожидают проверки</h2>
                    {queue.data?.map((p) => (
                      <button
                        className={selected.id === p.id ? 'selected' : ''}
                        key={p.id}
                        onClick={() => {
                          setSelectedId(p.id);
                          setRejecting(false);
                          setReason('');
                        }}
                      >
                        <span className="queue-label">
                          {p.hasPendingChanges ? 'Изменения новости' : 'Новая публикация'}
                        </span>
                        <strong>{p.title}</strong>
                        <small>
                          {p.author} ·{' '}
                          {communities.find((c) => c.id === p.communityId)?.name ||
                            'Личная публикация'}
                        </small>
                      </button>
                    ))}
                  </section>
                  <section className="panel moderation-review">
                    <div className="review-heading">
                      <Status status="pending" />
                      <span className="muted">{formatDate(selected.publishedAt)}</span>
                    </div>
                    <h2>{selected.title}</h2>
                    <p className="article-text">{selected.text}</p>
                    {selected.media.map((m) =>
                      m.kind === 'image' ? (
                        <img
                          key={m.id}
                          className="review-image"
                          src={m.url}
                          alt="Вложение заявки"
                        />
                      ) : (
                        <video
                          key={m.id}
                          className="real-video"
                          controls
                          preload="none"
                          playsInline
                          poster={m.posterUrl}
                          src={m.url}
                        />
                      ),
                    )}
                    {selected.source && (
                      <a
                        className="text-link"
                        target="_blank"
                        rel="noreferrer"
                        href={selected.source}
                      >
                        Источник
                      </a>
                    )}
                    {selected.hasPendingChanges && (
                      <Link className="text-link" to={`/news/${selected.id}`}>
                        Сравнить с опубликованной версией
                      </Link>
                    )}
                    {rejecting ? (
                      <form
                        className="reject-form"
                        onSubmit={(e) => {
                          e.preventDefault();
                          void decide(false);
                        }}
                      >
                        <label className="form-field">
                          Причина отклонения
                          <textarea
                            value={reason}
                            onChange={(e) => setReason(e.target.value)}
                            required
                            minLength={5}
                            maxLength={2000}
                            placeholder="Что автору нужно исправить?"
                            rows={3}
                          />
                        </label>
                        <button className="danger-button" disabled={busy}>
                          Отклонить с комментарием
                        </button>
                      </form>
                    ) : (
                      <div className="review-actions">
                        <button
                          className="primary-button"
                          disabled={busy}
                          onClick={() => void decide(true)}
                        >
                          <Check size={17} />
                          Одобрить
                        </button>
                        <button
                          className="secondary-button"
                          disabled={busy}
                          onClick={() => setRejecting(true)}
                        >
                          <X size={17} />
                          Отклонить
                        </button>
                      </div>
                    )}
                  </section>
                </div>
              ) : (
                !queue.loading && (
                  <EmptyState
                    title="Всё проверено"
                    text="Новые заявки появятся здесь. Обновите очередь, если ожидаете публикацию."
                    action={
                      <button className="secondary-button" onClick={queue.reload}>
                        Обновить очередь
                      </button>
                    }
                  />
                )
              )}
            </>
          )}
          {tab === 'reports' && (
            <>
              <button className="text-link" onClick={reports.reload}>
                Обновить жалобы
              </button>
              {reports.error && <ErrorState message={reports.error} retry={reports.reload} />}
              <div className="mail-list">
                {reports.data?.map((r) => (
                  <section key={r.id} className="panel moderation-review">
                    <span className={`status ${r.status === 'pending' ? 'pending' : 'published'}`}>
                      {r.status === 'pending' ? 'Новая жалоба' : 'Рассмотрена'}
                    </span>
                    <h2>{r.title}</h2>
                    <p>
                      <b>{r.reason}</b>
                    </p>
                    <p>{r.comment}</p>
                    {r.resolution && <div className="info-callout">{r.resolution}</div>}
                    <Link className="text-link" to={`/news/${r.newsId}`}>
                      Открыть новость
                    </Link>
                    {r.status === 'pending' && (
                      <form
                        className="reject-form"
                        onSubmit={async (e) => {
                          e.preventDefault();
                          const fields = new FormData(e.currentTarget);
                          try {
                            await json(
                              `/admin/reports/${r.id}`,
                              Object.fromEntries(fields),
                              'PATCH',
                            );
                            reports.reload();
                            notify('Жалоба рассмотрена');
                          } catch (err) {
                            notify((err as Error).message);
                          }
                        }}
                      >
                        <label className="form-field">
                          Результат рассмотрения
                          <textarea name="resolution" required maxLength={2000} />
                        </label>
                        <div className="review-actions">
                          <button className="primary-button">Завершить рассмотрение</button>
                          <button
                            type="button"
                            className="danger-button"
                            onClick={async () => {
                              if (!window.confirm('Скрыть эту публикацию из ленты?')) return;
                              try {
                                await json(`/admin/news/${r.newsId}/hide`, {});
                                await refresh();
                                notify('Публикация скрыта');
                              } catch (err) {
                                notify((err as Error).message);
                              }
                            }}
                          >
                            Скрыть публикацию
                          </button>
                        </div>
                      </form>
                    )}
                  </section>
                ))}
              </div>
              {!reports.loading && !reports.data?.length && (
                <EmptyState
                  title="Жалоб пока нет"
                  text="Обращения по доступным вам публикациям появятся здесь."
                />
              )}
            </>
          )}
          {tab === 'limits' && (
            <section className="panel limits-panel">
              <h2>{user.role === 'ADMIN' ? 'Паблики и лимиты' : 'Ваши паблики'}</h2>
              <p className="muted">
                Официальный статус и лимиты назначает администратор платформы. Создатель обычного
                паблика проверяет его публикации.
              </p>
              <div className="search-communities">
                {communities
                  .filter((c) => user.role === 'ADMIN' || c.ownerId === user.id)
                  .map((c) => (
                    <Link to={`/communities/${c.id}`} key={c.id}>
                      <Avatar community={c} size="small" />
                      <span>
                        {c.name} · {c.dailyLimit} в сутки
                      </span>
                      <ArrowRight size={16} />
                    </Link>
                  ))}
              </div>
            </section>
          )}
          {tab === 'audit' && (
            <section className="panel limits-panel">
              <h2>История решений</h2>
              <button className="text-link" onClick={audit.reload}>
                Обновить
              </button>
              {audit.data?.map((a) => (
                <div className="audit-row" key={a.id}>
                  <span className="post-date">
                    {formatDate(a.createdAt)} · {a.username}
                  </span>
                  <strong>{a.action}</strong>
                  <p>{a.detail}</p>
                </div>
              ))}
            </section>
          )}
        </>
      )}
    </AccountGate>
  );
}
export function NotificationsPage() {
  const { notifications, refresh, notify } = useApp();
  return (
    <AccountGate>
      <PageHeading
        title="Уведомления"
        subtitle="Решения по вашим публикациям."
        action={
          <button
            className="secondary-button"
            onClick={async () => {
              try {
                await json('/me/notifications/read', {});
                await refresh();
              } catch (e) {
                notify((e as Error).message);
              }
            }}
          >
            Прочитано
          </button>
        }
      />
      <div className="mail-list">
        {notifications.map((n) => (
          <article key={n.id} className={`panel mail-card ${n.read ? '' : 'unread'}`}>
            <span className="post-date">{formatDate(n.createdAt)}</span>
            <h2>
              <Bell size={17} />
              {n.message}
            </h2>
            <Link className="text-link" to="/my-posts">
              Посмотреть мои публикации <ArrowRight size={16} />
            </Link>
          </article>
        ))}
      </div>
      {!notifications.length && (
        <EmptyState
          title="Пока тихо"
          text="Когда модератор рассмотрит вашу новость, здесь появится сообщение."
        />
      )}
    </AccountGate>
  );
}
export function AboutPage() {
  return (
    <>
      <PageHeading
        title="Пульс университета"
        subtitle="Место, где университетская жизнь становится ближе."
      />
      <article className="panel about-page">
        <span className="eyebrow">УУНиТ · СТУДЕНЧЕСКИЕ НОВОСТИ</span>
        <h2>
          Один кампус.
          <br />
          Множество историй.
        </h2>
        <p>
          Читайте новости, находите паблики по интересам и предлагайте свои публикации. Общая лента
          доступна без регистрации.
        </p>
        <h3 id="rules">Правила сообщества</h3>
        <p>
          Проверяйте факты, уважайте других и указывайте источники. Каждая новость и её изменения
          проходят предмодерацию. Личные новости и официальные паблики проверяет администрация
          платформы. Обычные паблики модерируют их создатели.
        </p>
        <h3>Ваши данные</h3>
        <p>
          В этой версии аккаунты, новости, письма и медиа хранятся локально на компьютере, где
          запущен проект. Стартовые материалы добавлены для демонстрации и не являются реальными
          объявлениями университета.
        </p>
        <div className="about-links">
          <Link className="primary-button" to="/">
            Открыть ленту <ArrowRight size={17} />
          </Link>
          <Link className="secondary-button" to="/create">
            Предложить новость
          </Link>
        </div>
        <p className="field-note">
          Цвета: официальный брендбук УУНиТ. Фотографии корпусов: uust.ru. Знак «Пульс» — знак
          проекта.
        </p>
      </article>
    </>
  );
}
export function NotFoundPage() {
  return (
    <EmptyState
      title="Кажется, здесь пока тихо"
      text="Такой страницы нет. Но в ленте точно есть что почитать."
      action={
        <Link className="primary-button" to="/">
          Вернуться в ленту <ArrowRight size={16} />
        </Link>
      }
    />
  );
}
