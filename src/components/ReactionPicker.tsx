import { useEffect, useId, useRef, useState } from 'react';
import { Link } from 'react-router-dom';
import { useApp } from '../app/context';
import { api, json } from '../api/client';
import type { Post, ReactionKind, ReactionSnapshot } from '../domain/types';
import { postReactions, reactionChoices } from '../domain/reactions';

export function ReactionPicker({ post }: { post: Post }) {
  const { user, notify } = useApp();
  const [snapshot, setSnapshot] = useState(() => postReactions(post));
  const [open, setOpen] = useState(false);
  const [loading, setLoading] = useState(false);
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState('');
  const root = useRef<HTMLDivElement>(null);
  const toggle = useRef<HTMLButtonElement>(null);
  const request = useRef(0);
  const panelId = useId();

  useEffect(() => {
    setSnapshot(postReactions(post));
  }, [post.id, post.reactionCounts, post.myReaction, post.reactions, post.liked]);

  useEffect(() => {
    setOpen(false);
    setBusy(false);
    setLoading(false);
    setError('');
    return () => {
      request.current += 1;
    };
  }, [post.id, user?.id]);

  useEffect(() => {
    if (!open) return;
    const outside = (event: PointerEvent) => {
      if (!root.current?.contains(event.target as Node)) setOpen(false);
    };
    const escape = (event: KeyboardEvent) => {
      if (event.key === 'Escape') {
        setOpen(false);
        toggle.current?.focus();
      }
    };
    document.addEventListener('pointerdown', outside);
    document.addEventListener('keydown', escape);
    return () => {
      document.removeEventListener('pointerdown', outside);
      document.removeEventListener('keydown', escape);
    };
  }, [open]);

  async function refresh() {
    const current = ++request.current;
    setLoading(true);
    setError('');
    try {
      const result = await api<ReactionSnapshot>(`/news/${post.id}/reactions`);
      if (current === request.current) setSnapshot(result);
    } catch (cause) {
      if (current === request.current) setError((cause as Error).message);
    } finally {
      if (current === request.current) setLoading(false);
    }
  }

  async function choose(kind: ReactionKind) {
    if (busy || loading || !user) return;
    const current = ++request.current;
    const remove = snapshot.selected === kind;
    setBusy(true);
    setError('');
    try {
      if (remove) await api(`/news/${post.id}/reaction`, { method: 'DELETE' });
      else await json(`/news/${post.id}/reaction`, { kind }, 'PUT');
      const result = await api<ReactionSnapshot>(`/news/${post.id}/reactions`);
      if (current === request.current) {
        setSnapshot(result);
        setOpen(false);
        toggle.current?.focus();
        notify(remove ? 'Реакция убрана' : 'Реакция сохранена');
      }
    } catch (cause) {
      if (current === request.current) setError((cause as Error).message);
    } finally {
      if (current === request.current) setBusy(false);
    }
  }

  const selected = reactionChoices.find((choice) => choice.kind === snapshot.selected);
  const leading =
    selected ??
    reactionChoices.find((choice) => snapshot.counts[choice.kind] > 0) ??
    reactionChoices[0];
  return (
    <div className="reaction-picker" ref={root}>
      <button
        type="button"
        ref={toggle}
        className={`reaction reaction-toggle ${snapshot.selected ? 'has-reaction' : ''}`}
        aria-label={`Реакции: ${snapshot.total}${selected ? `. Ваша реакция: ${selected.label}` : ''}`}
        aria-expanded={open}
        aria-controls={open ? panelId : undefined}
        onClick={() => {
          if (!open) void refresh();
          setOpen(!open);
        }}
        disabled={busy}
      >
        <span aria-hidden="true" className="reaction-emoji">
          {leading.emoji}
        </span>
        <span>{snapshot.total}</span>
        <span aria-hidden="true" className="reaction-add">
          +
        </span>
      </button>
      {open && (
        <div
          id={panelId}
          className="reaction-panel"
          role="group"
          aria-label="Выберите реакцию"
          aria-busy={loading || busy}
        >
          <p className="reaction-heading">Как вам новость?</p>
          <div className="reaction-options">
            {reactionChoices.map(({ kind, emoji, label }) => (
              <button
                type="button"
                key={kind}
                className={`reaction-option ${snapshot.selected === kind ? 'selected' : ''}`}
                aria-label={`${label}: ${snapshot.counts[kind]}`}
                aria-pressed={snapshot.selected === kind}
                title={snapshot.selected === kind ? `Убрать реакцию «${label}»` : label}
                disabled={!user || busy || loading}
                onClick={() => void choose(kind)}
              >
                <span aria-hidden="true" className="reaction-emoji">
                  {emoji}
                </span>
                <span className="reaction-option-label">{label}</span>
                <span>{snapshot.counts[kind]}</span>
              </button>
            ))}
          </div>
          {(loading || busy) && (
            <p className="reaction-note" role="status">
              {busy ? 'Сохраняем…' : 'Обновляем реакции…'}
            </p>
          )}
          {!user && (
            <p className="reaction-note">
              <Link to={`/auth/login?returnTo=${encodeURIComponent(`/news/${post.id}`)}`}>
                Войдите
              </Link>
              , чтобы поставить реакцию.
            </p>
          )}
          {user && !loading && !busy && (
            <p className="reaction-note">
              Одна реакция на новость. Нажмите выбранную ещё раз, чтобы убрать.
            </p>
          )}
          {error && (
            <div className="reaction-error" role="alert">
              <p>{error}</p>
              <button
                type="button"
                className="text-link"
                onClick={() => void refresh()}
                disabled={busy || loading}
              >
                Обновить реакции
              </button>
            </div>
          )}
        </div>
      )}
    </div>
  );
}
