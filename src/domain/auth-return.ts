const uuid = '[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}';
const communitySubmit = new RegExp(`^/communities/${uuid}/submit$`, 'i');
const legacyCommunitySubmit = new RegExp(`^/create\\?community=${uuid}$`, 'i');

export function authReturnTo(path: string | null): string {
  if (
    path === '/cms' ||
    path === '/create' ||
    (path && (communitySubmit.test(path) || legacyCommunitySubmit.test(path)))
  )
    return path;
  return '/profile';
}
