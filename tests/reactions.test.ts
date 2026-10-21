import assert from 'node:assert/strict';
import test from 'node:test';
import { postReactions } from '../src/domain/reactions.ts';

test('old like-only news retains reaction totals and the current user selection', () => {
  assert.deepEqual(postReactions({ reactions: 3, liked: true }), {
    counts: { like: 3, celebrate: 0, insightful: 0, support: 0 },
    selected: 'like',
    total: 3,
  });
  assert.equal(postReactions({ reactions: 0, liked: false }).selected, null);
});

test('emoji reaction data takes precedence over the compatibility like flags', () => {
  const counts = { like: 2, celebrate: 3, insightful: 4, support: 1 };
  assert.deepEqual(
    postReactions({ reactions: 10, liked: true, reactionCounts: counts, myReaction: 'insightful' }),
    {
      counts,
      selected: 'insightful',
      total: 10,
    },
  );
  assert.equal(
    postReactions({ reactions: 10, liked: true, reactionCounts: counts, myReaction: null })
      .selected,
    null,
  );
});
