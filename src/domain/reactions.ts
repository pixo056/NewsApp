import type { Post, ReactionKind, ReactionSnapshot } from './types';

export const reactionChoices: { kind: ReactionKind; emoji: string; label: string }[] = [
  { kind: 'like', emoji: '👍', label: 'Нравится' },
  { kind: 'celebrate', emoji: '🎉', label: 'Поздравляю' },
  { kind: 'insightful', emoji: '💡', label: 'Интересно' },
  { kind: 'support', emoji: '❤️', label: 'Поддерживаю' },
];

export function postReactions(
  post: Pick<Post, 'reactions' | 'liked' | 'reactionCounts' | 'myReaction'>,
): ReactionSnapshot {
  const counts = post.reactionCounts ?? {
    like: post.reactions,
    celebrate: 0,
    insightful: 0,
    support: 0,
  };
  return {
    counts,
    selected: post.myReaction === undefined ? (post.liked ? 'like' : null) : post.myReaction,
    total: Object.values(counts).reduce((sum, count) => sum + count, 0),
  };
}
