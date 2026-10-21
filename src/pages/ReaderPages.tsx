import { useCallback, useEffect, useRef, useState } from 'react';
import { Link, NavLink, useNavigate, useParams, useSearchParams } from 'react-router-dom';
import {
  ArrowDown,
  ArrowLeft,
  ArrowRight,
  ArrowUpRight,
  Bookmark,
  Check,
  Compass,
  Flag,
  Plus,
  Search,
} from 'lucide-react';
import { categories } from '../data/categories';
import type { Category, Page, Post } from '../domain/types';
import { useApp } from '../app/context';
import { api, json } from '../api/client';
import {
  AccountGate,
  Avatar,
  EmptyState,
  ErrorState,
  Loading,
  PageHeading,
  PostCard,
  Verified,
  formatDate,
} from '../components/ui';

function Categories({
  value,
  setValue,
}: {
  value: Category;
  setValue: (category: Category) => void;
}) {
  return (
    <div className="category-scroll" aria-label="Категории новостей">
      {categories.map((c) => (
        <button
          key={c}
          className={`category-chip ${c === value ? 'active' : ''}`}
          onClick={() => setValue(c)}
          aria-pressed={value === c}
        >
          {c}
        </button>
      ))}
    </div>
  );
}
export function NewsList({
  query = '',
  empty = 'Здесь пока нет новостей',
}: {
  query?: string;
  empty?: string;
}) {
  const { user, saved, followed } = useApp();
  const [items, setItems] = useState<Post[]>([]);
  const [cursor, setCursor] = useState<string>();
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState('');
  const [retry, setRetry] = useState(0);
  const sentinel = useRef<HTMLDivElement>(null);
  const generation = useRef(0);
  const busy = useRef(false);
  useEffect(() => {
    const version = ++generation.current;
    setLoading(true);
    busy.current = true;
    setError('');
    setItems([]);
    setCursor(undefined);
    api<Page<Post>>(`/news?limit=5&${query}`)
      .then((page) => {
        if (version === generation.current) {
          setItems(page.items);
          setCursor(page.nextCursor);
        }
      })
      .catch((e) => {
        if (version === generation.current) setError(e.message);
      })
      .finally(() => {
        if (version === generation.current) {
          setLoading(false);
          busy.current = false;
        }
      });
    return () => {
      generation.current++;
    };
  }, [
    query,
    user?.id,
    retry,
    query.includes('saved=true') ? saved.join(',') : '',
    query.includes('subscriptions=true') ? followed.join(',') : '',
  ]);
  const more = useCallback(async () => {
    if (busy.current || !cursor) return;
    busy.current = true;
    const version = generation.current;
    setLoading(true);
    try {
      const page = await api<Page<Post>>(
        `/news?limit=5&${query}&cursor=${encodeURIComponent(cursor)}`,
      );
      if (version === generation.current) {
        setItems((old) => [
          ...old,
          ...page.items.filter((p) => !old.some((item) => item.id === p.id)),
        ]);
        setCursor(page.nextCursor);
        setError('');
      }
    } catch (e) {
      if (version === generation.current) setError((e as Error).message);
    } finally {
      if (version === generation.current) {
        setLoading(false);
        busy.current = false;
      }
    }
  }, [cursor, query]);
  useEffect(() => {
    if (!cursor || loading || error || !sentinel.current) return;
    const observer = new IntersectionObserver(
      (entries) => {
        if (entries[0].isIntersecting) void more();
      },
      { rootMargin: '180px' },
    );
    observer.observe(sentinel.current);
    return () => observer.disconnect();
  }, [cursor, loading, error, more]);
  return (
    <div className="news-list">
      {items.map((p) => (
        <PostCard key={`${p.id}-${user?.id || 'guest'}`} post={p} />
      ))}
      {error && (
        <ErrorState
          message={error}
          retry={() => (items.length ? void more() : setRetry(retry + 1))}
        />
      )}
      {loading && <Loading />}
      {!loading && !error && !items.length && (
        <EmptyState
          title={empty}
          text="Выберите другую категорию, подпишитесь на паблик или предложите свою историю."
          action={
            <Link className="secondary-button" to="/communities">
              Посмотреть паблики
            </Link>
          }
        />
      )}
      {cursor && !loading && (
        <div ref={sentinel}>
          <button className="load-more" onClick={() => void more()}>
            Ещё новости <ArrowDown size={16} />
          </button>
        </div>
      )}
      {items.length > 0 && !cursor && !loading && (
        <p className="feed-end">Вы всё прочитали. Самое время создать что-нибудь своё.</p>
      )}
    </div>
  );
}
export function FeedPage({ subscriptions = false }: { subscriptions?: boolean }) {
  const [category, setCategory] = useState<Category>('Все');
  const { followed } = useApp();
  const feed = (
    <>
      <div className="feed-controls">
        <nav className="feed-tabs" aria-label="Вкладки ленты">
          <NavLink to="/" end>
            Все новости
          </NavLink>
          <NavLink to="/subscriptions">
            Подписки<span>{followed.length}</span>
          </NavLink>
        </nav>
        <span className="sort-label">
          <ArrowDown size={14} />
          Сначала новые
        </span>
      </div>
      <Categories value={category} setValue={setCategory} />
      <NewsList query={`category=${encodeURIComponent(category)}&subscriptions=${subscriptions}`} />
    </>
  );
  return (
    <>
      <PageHeading
        title="В ритме университета"
        subtitle="Всё, чем живёт кампус. И немного больше."
        action={
          <span className="date-pill">
            {new Date().toLocaleDateString('ru', {
              day: 'numeric',
              month: 'long',
              weekday: 'long',
            })}
          </span>
        }
      />
      <section className="hero-card">
        <div className="hero-copy">
          <span className="eyebrow">
            <span />
            ЖИЗНЬ КАМПУСА
          </span>
          <h2>
            Здесь происходят
            <br />
            твои завтра.
          </h2>
          <p>
            Находи своих. Делись важным.
            <br />
            Будь частью университетской жизни.
          </p>
          <Link to="/communities">
            Найти своё сообщество <ArrowUpRight size={17} />
          </Link>
        </div>
        <div className="hero-art" aria-hidden="true">
          <div className="orbit orbit-one" />
          <div className="orbit orbit-two" />
          <div className="star star-one">✳</div>
          <div className="hero-sticker">
            УУНиТ
            <br />
            <b>это мы</b>
            <span>↗</span>
          </div>
          <div className="star star-two">✦</div>
          <div className="hero-mini-card">
            <span>ВМЕСТЕ</span>
            <b>
              больше
              <br />
              возможностей
            </b>
            <div>
              ✦ <span>Идей хватит на всех</span>
            </div>
          </div>
        </div>
      </section>
      {subscriptions ? <AccountGate>{feed}</AccountGate> : feed}
    </>
  );
}
export function CommunitiesPage() {
  const { communities, followed, toggleFollow, loading } = useApp();
  const [query, setQuery] = useState('');
  const [onlyFollowed, setOnlyFollowed] = useState(false);
  const filtered = communities.filter(
    (c) =>
      c.name.toLowerCase().includes(query.toLowerCase()) &&
      (!onlyFollowed || followed.includes(c.id)),
  );
  return (
    <>
      <PageHeading
        title="Найдите своих"
        subtitle="Люди, интересы и события, которые нас объединяют."
        action={
          <Link to="/communities/new" className="primary-button">
            <Plus size={17} />
            Создать
          </Link>
        }
      />
      <div className="panel directory-toolbar">
        <label className="field-search">
          <Search size={19} />
          <input
            placeholder="Название паблика"
            aria-label="Найти паблик"
            value={query}
            onChange={(e) => setQuery(e.target.value)}
          />
        </label>
        <div className="segmented">
          <button className={!onlyFollowed ? 'active' : ''} onClick={() => setOnlyFollowed(false)}>
            Все паблики
          </button>
          <button className={onlyFollowed ? 'active' : ''} onClick={() => setOnlyFollowed(true)}>
            Мои подписки ({followed.length})
          </button>
        </div>
      </div>
      {loading ? (
        <Loading />
      ) : (
        <div className="community-grid">
          {filtered.map((c) => (
            <article className="community-card" key={c.id}>
              <div className={`community-decoration ${c.color}`}>
                <span>✳</span>
                <span>{c.category}</span>
              </div>
              <Avatar community={c} size="large" />
              <Link to={`/communities/${c.id}`}>
                <h2>
                  {c.name}
                  {c.verified && <Verified />}
                </h2>
              </Link>
              <p>{c.description}</p>
              <span className="muted">{c.members} подписчиков</span>
              <div className="community-card-actions">
                <button
                  className={followed.includes(c.id) ? 'secondary-button' : 'primary-button'}
                  onClick={() => void toggleFollow(c.id)}
                >
                  {followed.includes(c.id) ? (
                    <>
                      <Check size={16} />
                      Вы подписаны
                    </>
                  ) : (
                    'Подписаться'
                  )}
                </button>
                <Link
                  to={`/communities/${c.id}`}
                  className="icon-button"
                  aria-label={`Открыть ${c.name}`}
                >
                  <ArrowUpRight size={19} />
                </Link>
              </div>
            </article>
          ))}
        </div>
      )}
      {!loading && !filtered.length && (
        <EmptyState
          title="Паблики не найдены"
          text="Попробуйте другое название или откройте все паблики."
        />
      )}
    </>
  );
}
export function CreateCommunityPage() {
  const { refresh, notify, user } = useApp();
  const navigate = useNavigate();
  const [error, setError] = useState('');
  const [busy, setBusy] = useState(false);
  return (
    <AccountGate>
      <PageHeading
        title="Создайте своё сообщество"
        subtitle="Вы станете владельцем и модератором новостей этого паблика."
        back="/communities"
      />
      <form
        className="panel editor-section"
        onSubmit={async (e) => {
          e.preventDefault();
          setBusy(true);
          setError('');
          const fields = new FormData(e.currentTarget);
          try {
            const created = await json<{ id: string }>('/communities', Object.fromEntries(fields));
            await refresh();
            notify('Паблик создан. Его новости проходят вашу модерацию.');
            navigate(`/communities/${created.id}`);
          } catch (err) {
            setError((err as Error).message);
          } finally {
            setBusy(false);
          }
        }}
      >
        <label className="form-field">
          Название
          <input
            name="name"
            required
            minLength={3}
            maxLength={100}
            placeholder="Как называется ваше сообщество?"
          />
        </label>
        <label className="form-field">
          Описание
          <textarea name="description" required maxLength={500} rows={4} />
        </label>
        <label className="form-field">
          Категория
          <select name="category">
            {categories
              .filter((c) => c !== 'Все')
              .map((c) => (
                <option key={c}>{c}</option>
              ))}
          </select>
        </label>
        <label className="form-field">
          Цвет
          <select name="color">
            <option value="purple">Фиолетовый</option>
            <option value="lime">Лаймовый</option>
            <option value="mint">Мятный</option>
            <option value="peach">Персиковый</option>
            <option value="pink">Розовый</option>
            <option value="sky">Голубой</option>
          </select>
        </label>
        <div className="info-callout">
          Официальный статус УУНиТ может назначить только администратор платформы.
        </div>
        {!user?.verified && (
          <p className="field-note">Перед созданием подтвердите почту в профиле.</p>
        )}
        {error && (
          <p className="form-error" role="alert">
            {error}
          </p>
        )}
        <button className="primary-button" disabled={busy}>
          {busy ? 'Создаём…' : 'Создать паблик'}
          <Plus size={17} />
        </button>
      </form>
    </AccountGate>
  );
}
export function CommunityPage() {
  const { id } = useParams();
  const { communities, loading, followed, toggleFollow, refresh, notify, user } = useApp();
  const community = communities.find((c) => c.id === id);
  const [editing, setEditing] = useState(false);
  if (loading) return <Loading />;
  if (!community) return <NotFound />;
  return (
    <>
      <PageHeading title="Паблик" back="/communities" />
      <section className="community-profile panel">
        <div className={`community-cover ${community.color}`}>
          <span>ВМЕСТЕ ИНТЕРЕСНЕЕ</span>
          <b>✳</b>
        </div>
        <div className="community-profile-body">
          <Avatar community={community} size="large" />
          <h1>
            {community.name}
            {community.verified && <Verified />}
          </h1>
          <p>{community.description}</p>
          <p className="field-note">
            {community.verified
              ? 'Публикации проверяет администрация платформы.'
              : 'Публикации проверяет создатель паблика.'}
          </p>
          <div className="community-profile-bottom">
            <span>
              {community.members} подписчиков · {community.category}
            </span>
            <button
              className={followed.includes(community.id) ? 'secondary-button' : 'primary-button'}
              onClick={() => void toggleFollow(community.id)}
            >
              {followed.includes(community.id) ? 'Отписаться' : 'Подписаться'}
            </button>
          </div>
          <div className="community-tools">
            <Link to={`/create?community=${id}`} className="text-link">
              Предложить новость <ArrowUpRight size={15} />
            </Link>
            {community.canManage && (
              <>
                <Link to="/admin" className="text-link">
                  Модерация
                </Link>
                <button className="text-link" onClick={() => setEditing(!editing)}>
                  Настроить паблик
                </button>
              </>
            )}
          </div>
          {editing && (
            <form
              className="community-edit"
              onSubmit={async (e) => {
                e.preventDefault();
                const data = new FormData(e.currentTarget);
                try {
                  await json(
                    `/communities/${id}`,
                    { name: data.get('name'), description: data.get('description') },
                    'PATCH',
                  );
                  if (user?.role === 'ADMIN')
                    await json(
                      `/admin/communities/${id}`,
                      {
                        dailyLimit: Number(data.get('dailyLimit')),
                        official: data.get('official') === 'on',
                      },
                      'PATCH',
                    );
                  await refresh();
                  setEditing(false);
                  notify('Паблик обновлён');
                } catch (err) {
                  notify((err as Error).message);
                }
              }}
            >
              <label className="form-field">
                Название
                <input
                  name="name"
                  required
                  minLength={3}
                  maxLength={100}
                  defaultValue={community.name}
                />
              </label>
              <label className="form-field">
                Описание
                <textarea name="description" maxLength={500} defaultValue={community.description} />
              </label>
              {user?.role === 'ADMIN' && (
                <>
                  <label className="form-field">
                    Лимит в сутки
                    <input
                      name="dailyLimit"
                      type="number"
                      min={1}
                      max={1000}
                      defaultValue={community.dailyLimit}
                    />
                  </label>
                  <label className="radio-row">
                    <input name="official" type="checkbox" defaultChecked={community.verified} />
                    Официальный паблик УУНиТ
                  </label>
                </>
              )}
              <button className="primary-button">Сохранить</button>
            </form>
          )}
        </div>
      </section>
      <h2 className="section-title">Публикации паблика</h2>
      <NewsList query={`communityId=${id}`} />
    </>
  );
}
function NotFound() {
  return (
    <EmptyState
      title="Страница не найдена"
      text="Возможно, ссылка изменилась или публикация ещё проходит проверку."
      action={
        <Link className="primary-button" to="/">
          Открыть ленту
        </Link>
      }
    />
  );
}
export function ArticlePage() {
  const { id } = useParams();
  const { communities, saved, toggleSave } = useApp();
  const [post, setPost] = useState<Post>();
  const [error, setError] = useState('');
  useEffect(() => {
    let active = true;
    setPost(undefined);
    setError('');
    api<Post>(`/news/${id}`)
      .then((p) => {
        if (active) {
          setPost(p);
          if (p.status === 'published')
            void json<{ views: number; counted: boolean }>(`/news/${id}/view`, {})
              .then(({ views }) => {
                if (active)
                  setPost((current) =>
                    current?.id === p.id ? { ...current, views: String(views) } : current,
                  );
              })
              .catch(() => {});
        }
      })
      .catch((e) => {
        if (active) setError(e.message);
      });
    return () => {
      active = false;
    };
  }, [id]);
  if (error) return <ErrorState message={error} />;
  if (!post) return <Loading />;
  const community = communities.find((c) => c.id === post.communityId);
  return (
    <>
      <Link to="/" className="back-link article-back">
        <ArrowLeft size={17} />В ленту
      </Link>
      <article className="article panel">
        <span className="article-category">{post.category}</span>
        <h1>{post.title}</h1>
        <div className="post-author">
          {community ? (
            <Link className="author-link" to={`/communities/${community.id}`}>
              <Avatar community={community} />
              <span>
                <span className="author-name">
                  {community.name}
                  {community.verified && <Verified />}
                </span>
                <span className="post-date">{formatDate(post.publishedAt)}</span>
              </span>
            </Link>
          ) : (
            <span className="muted">
              {post.author} · {formatDate(post.publishedAt)}
            </span>
          )}
        </div>
        {post.media.map((m) =>
          m.kind === 'image' ? (
            <img key={m.id} className="article-image" src={m.url} alt={post.title} />
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
        <div className="article-text">
          {post.text.split('\n\n').map((p, i) => (
            <p key={i}>{p}</p>
          ))}
        </div>
        {post.source && (
          <a className="source-link" href={post.source} target="_blank" rel="noreferrer">
            Открыть источник <ArrowUpRight size={15} />
          </a>
        )}
        <div className="article-bottom">
          <button className="secondary-button" onClick={() => void toggleSave(post.id)}>
            <Bookmark size={17} />
            {saved.includes(post.id) ? 'Сохранено' : 'Сохранить'}
          </button>
          <Link className="muted text-link" to={`/news/${post.id}/report`}>
            <Flag size={15} />
            Пожаловаться
          </Link>
        </div>
      </article>
      <Link className="text-link next-read" to="/">
        Другие истории кампуса <ArrowRight size={18} />
      </Link>
    </>
  );
}
export function SavedPage() {
  return (
    <AccountGate>
      <PageHeading
        title="Сохранённое"
        subtitle="Интересное не потеряется. Вернитесь, когда будет время."
      />
      <NewsList query="saved=true" empty="Здесь будут ваши находки" />
    </AccountGate>
  );
}
export function SearchPage() {
  const [params, setParams] = useSearchParams();
  const query = params.get('q') || '';
  const [value, setValue] = useState(query);
  const { communities } = useApp();
  useEffect(() => setValue(query), [query]);
  const found = communities.filter((c) => c.name.toLowerCase().includes(query.toLowerCase()));
  return (
    <>
      <PageHeading title="Поиск" subtitle="Найдите историю, событие или своё сообщество." />
      <form
        className="panel search-form"
        onSubmit={(e) => {
          e.preventDefault();
          setParams({ q: value.trim() });
        }}
      >
        <label className="field-search">
          <Search size={18} />
          <input
            value={value}
            maxLength={200}
            onChange={(e) => setValue(e.target.value)}
            placeholder="Что ищем?"
            aria-label="Поисковый запрос"
          />
        </label>
        <button className="primary-button">Найти</button>
      </form>
      {query ? (
        <>
          <h2 className="section-title">
            Паблики <span>{found.length}</span>
          </h2>
          <div className="panel search-communities">
            {found.map((c) => (
              <Link key={c.id} to={`/communities/${c.id}`}>
                <Avatar community={c} />
                <span>{c.name}</span>
                <ArrowUpRight size={16} />
              </Link>
            ))}
            {!found.length && <p className="muted">Нет совпадений</p>}
          </div>
          <h2 className="section-title">Новости</h2>
          <NewsList
            query={`q=${encodeURIComponent(query)}`}
            empty={`Ничего не найдено по запросу «${query}»`}
          />
        </>
      ) : (
        <EmptyState
          title="Что интересного в кампусе?"
          text="Введите слово или название паблика в строке поиска."
          action={<Compass size={30} />}
        />
      )}
    </>
  );
}
