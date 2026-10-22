import { ArrowLeft, ArrowUpRight, BadgeCheck, Bookmark, Eye, Flag } from 'lucide-react';
import { Link } from 'react-router-dom';
import type { ReactNode } from 'react';
import type { Community, Post } from '../domain/types';
import { useApp } from '../app/context';
import { ReactionPicker } from './ReactionPicker';
export function Avatar({ community, size = '' }: { community: Community; size?: string }) {
  return (
    <span aria-hidden="true" className={`avatar ${community.color} ${size}`}>
      {community.initials}
    </span>
  );
}
export function Verified() {
  return <BadgeCheck className="verified" size={15} aria-label="Официальный паблик" />;
}
export function PageHeading({
  title,
  subtitle,
  action,
  back,
}: {
  title: string;
  subtitle?: string;
  action?: ReactNode;
  back?: string;
}) {
  return (
    <header className="page-heading">
      {back && (
        <Link to={back} className="back-link">
          <ArrowLeft size={16} />
          Назад
        </Link>
      )}
      <div className="heading-line">
        <div>
          <h1>{title}</h1>
          {subtitle && <p>{subtitle}</p>}
        </div>
        {action}
      </div>
    </header>
  );
}
export function EmptyState({
  title,
  text,
  action,
}: {
  title: string;
  text: string;
  action?: ReactNode;
}) {
  return (
    <div className="empty-state">
      <span className="empty-symbol">◌</span>
      <h2>{title}</h2>
      <p>{text}</p>
      {action}
    </div>
  );
}
export function Status({ status }: { status: Post['status'] }) {
  return (
    <span className={`status ${status}`}>
      {{ pending: 'На модерации', published: 'Опубликована', rejected: 'Отклонена' }[status]}
    </span>
  );
}
export function formatDate(iso: string) {
  return new Intl.DateTimeFormat('ru', {
    day: 'numeric',
    month: 'long',
    hour: '2-digit',
    minute: '2-digit',
    timeZone: 'Asia/Yekaterinburg',
  }).format(new Date(iso));
}
export function Loading() {
  return (
    <div className="loading-state" role="status">
      <span className="spinner" />
      Загружаем…
    </div>
  );
}
export function ErrorState({ message, retry }: { message: string; retry?: () => void }) {
  return (
    <div className="panel error-state" role="alert">
      <p>{message}</p>
      {retry && (
        <button className="secondary-button" onClick={retry}>
          Попробовать снова
        </button>
      )}
    </div>
  );
}
export function AccountGate({ children, returnTo }: { children: ReactNode; returnTo?: string }) {
  const { user, loading } = useApp();
  if (loading) return <Loading />;
  if (!user)
    return (
      <EmptyState
        title="Войдите в свой аккаунт"
        text="Читайте общую ленту без регистрации. Для этого действия нужен аккаунт."
        action={
          <Link
            className="primary-button"
            to={returnTo ? `/auth/login?returnTo=${encodeURIComponent(returnTo)}` : '/auth/login'}
          >
            Войти или зарегистрироваться
          </Link>
        }
      />
    );
  return <>{children}</>;
}
export function PostMedia({ post }: { post: Post }) {
  return (
    <>
      {post.videoUrl && (
        <video
          className="real-video"
          controls
          preload="none"
          poster={post.poster}
          playsInline
          src={post.videoUrl}
        >
          Ваш браузер не поддерживает видео.
        </video>
      )}
      {!post.videoUrl && post.image && (
        <Link to={`/news/${post.id}?revision=${post.revisionId}`} className="post-image-link">
          <img className="post-image" src={post.image} alt={post.title} loading="lazy" />
          <span className="image-tag">
            Открыть новость <ArrowUpRight size={15} />
          </span>
        </Link>
      )}
    </>
  );
}
export function PostCard({ post }: { post: Post }) {
  const { saved, toggleSave, communities } = useApp();
  const community = communities.find((c) => c.id === post.communityId);
  return (
    <article className="post-card">
      <header className="post-author">
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
          <Link to={`/news/${post.id}?revision=${post.revisionId}`} className="author-link">
            <span className="avatar lavender">{post.author.slice(0, 1)}</span>
            <span>
              <span className="author-name">{post.author}</span>
              <span className="post-date">{formatDate(post.publishedAt)}</span>
            </span>
          </Link>
        )}
        <span className="category-label">{post.category}</span>
      </header>
      <Link className="post-body" to={`/news/${post.id}?revision=${post.revisionId}`}>
        <h2>{post.title}</h2>
        {post.text && <p>{post.text}</p>}
      </Link>
      <PostMedia post={post} />
      <footer className="post-actions">
        <ReactionPicker post={post} />
        <span className="views">
          <Eye size={17} />
          {post.views}
        </span>
        <div className="action-spacer" />
        <Link
          className="icon-button subtle"
          to={`/news/${post.id}/report`}
          aria-label="Пожаловаться на новость"
        >
          <Flag size={17} />
        </Link>
        <button
          className={`icon-button ${saved.includes(post.id) ? 'is-saved' : ''}`}
          aria-label={saved.includes(post.id) ? 'Удалить из сохранённого' : 'Сохранить новость'}
          aria-pressed={saved.includes(post.id)}
          onClick={() => void toggleSave(post.id)}
        >
          <Bookmark size={20} fill={saved.includes(post.id) ? 'currentColor' : 'none'} />
        </button>
      </footer>
    </article>
  );
}
