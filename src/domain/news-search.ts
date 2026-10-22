/** Same Russian/ASCII case folding, е/ё and whitespace rules as PostgreSQL news_search(). */
export function normalizeNewsSearch(value: string): string {
  return value
    .replace(/[A-ZА-ЯЁё]/g, (letter) =>
      letter === 'Ё' || letter === 'ё' ? 'е' : letter.toLowerCase(),
    )
    .replace(/[\s\u0085]+/gu, ' ')
    .trim();
}

export function matchingCommunities<T extends { name: string; description: string }>(
  items: T[],
  query: string,
): T[] {
  const q = normalizeNewsSearch(query);
  return items
    .filter(
      (item) =>
        normalizeNewsSearch(item.name).includes(q) ||
        normalizeNewsSearch(item.description).includes(q),
    )
    .sort(
      (a, b) =>
        Number(normalizeNewsSearch(b.name) === q) - Number(normalizeNewsSearch(a.name) === q),
    );
}
