import assert from 'node:assert/strict';
import { readFile } from 'node:fs/promises';
import { randomUUID } from 'node:crypto';

// Local-only acceptance workflow. Credentials never leave this machine or appear in output.
const base = 'http://localhost:5173/api';
const config = JSON.parse(
  await readFile(new URL('../.local/config.json', import.meta.url), 'utf8'),
);
class Client {
  cookies = new Map();
  async request(path, body, method = body === undefined ? 'GET' : 'POST', form = false) {
    const headers = {};
    if (method !== 'GET') {
      const csrf = await this.ok('/auth/csrf');
      headers[csrf.headerName] = csrf.token;
    }
    if (this.cookies.size)
      headers.Cookie = [...this.cookies].map(([name, value]) => `${name}=${value}`).join('; ');
    if (body !== undefined) {
      headers['Content-Type'] = form ? 'application/x-www-form-urlencoded' : 'application/json';
      body = form ? new URLSearchParams(body) : JSON.stringify(body);
    }
    const response = await fetch(`${base}${path}`, { method, body, headers });
    for (const cookie of response.headers.getSetCookie()) {
      const first = cookie.split(';')[0];
      const index = first.indexOf('=');
      this.cookies.set(first.slice(0, index), first.slice(index + 1));
    }
    const text = await response.text();
    return { status: response.status, data: text ? JSON.parse(text) : null };
  }
  async ok(...args) {
    const response = await this.request(...args);
    assert.ok(
      response.status >= 200 && response.status < 300,
      `${args[0]}: HTTP ${response.status}, ${response.data?.message || ''}`,
    );
    return response.data;
  }
  login(username, password) {
    return this.request('/auth/login', { username, password }, 'POST', true);
  }
}

const guest = new Client(),
  admin = new Client(),
  owner = new Client(),
  author = new Client();
const nonce = randomUUID().slice(0, 8);
const username = `cms.${nonce}`,
  email = `${username}@example.invalid`,
  password = `CmsLocal!${nonce}`;
assert.equal((await admin.login('uunit.admin', config.adminPassword)).status, 200);
assert.equal((await owner.login('club.owner', config.ownerPassword)).status, 200);
assert.equal((await guest.request('/cms/news')).status, 401);
assert.equal((await owner.request('/cms/users')).status, 403);
assert.equal((await owner.request('/cms/audit')).status, 403);
assert.equal((await owner.request('/cms/reports')).status, 403);
assert.equal((await owner.ok('/cms/summary')).reports, 0);
await guest.ok('/auth/register', { username, email, name: 'Проверка CMS', password });
const letter = (await guest.ok('/dev/mailbox')).find((mail) => mail.recipient === email);
await guest.ok('/auth/verify-email', {
  token: new URL(letter.actionUrl, base).searchParams.get('token'),
});
assert.equal((await author.login(username, password)).status, 200);
const me = (await author.ok('/auth/me')).user;
const input = {
  title: `Проверка CMS ${nonce}`,
  text: 'Проверяем управление редакцией через настоящие HTTP-запросы, сохранение автора и предмодерацию изменений.',
  category: 'Технологии',
  communityId: null,
  source: '',
  mediaIds: [],
};
const created = await author.ok('/news', input);
try {
  const listed = await admin.ok(`/cms/news?q=${nonce}&status=pending`);
  assert.equal(listed.total, 1);
  assert.equal((await owner.ok(`/cms/news?q=${nonce}`)).total, 0);
  assert.equal((await owner.request(`/cms/news/${created.id}`)).status, 404);
  let post = await admin.ok(`/cms/news/${created.id}`);
  assert.equal(
    (await owner.request(`/admin/revisions/${post.revisionId}/approve`, {})).status,
    403,
  );
  assert.equal(
    (await owner.request(`/cms/news/${created.id}/visibility`, { hidden: false })).status,
    403,
  );
  await admin.ok(`/admin/revisions/${post.revisionId}/approve`, {});
  await admin.ok(`/cms/news/${created.id}`, { ...input, title: `Правка CMS ${nonce}` }, 'PATCH');
  assert.equal((await guest.ok(`/news/${created.id}`)).title, input.title);
  post = await admin.ok(`/cms/news/${created.id}`);
  assert.equal(post.authorId, me.id);
  assert.equal(post.status, 'pending');
  await admin.ok(`/admin/revisions/${post.revisionId}/approve`, {});
  assert.equal((await guest.ok(`/news/${created.id}`)).title, `Правка CMS ${nonce}`);
  await admin.ok(`/cms/news/${created.id}/visibility`, { hidden: true });
  assert.equal((await guest.request(`/news/${created.id}`)).status, 404);
  assert.equal((await admin.ok(`/cms/news?q=${nonce}&status=hidden`)).total, 1);
  await admin.ok(`/cms/news/${created.id}/visibility`, { hidden: false });
  assert.equal((await guest.ok(`/news/${created.id}`)).title, `Правка CMS ${nonce}`);

  const users = await admin.ok(`/cms/users?q=${username}`);
  assert.equal(users.total, 1);
  assert.equal(users.items[0].id, me.id);
  assert.equal('password_hash' in users.items[0], false);
  await admin.ok(
    `/cms/users/${me.id}`,
    { blocked: true, blockReason: 'Проверка ограничения доступа CMS' },
    'PATCH',
  );
  assert.equal((await author.request('/auth/me')).status, 401);
  assert.equal((await author.login(username, password)).status, 401);
  await admin.ok(`/cms/users/${me.id}`, { blocked: false }, 'PATCH');
  assert.equal((await author.login(username, password)).status, 200);

  await author.ok(`/news/${created.id}/reports`, {
    reason: 'Проверка CMS',
    comment: 'Автоматический локальный сценарий',
  });
  const reports = await admin.ok(`/cms/reports?q=${nonce}&status=pending`);
  assert.equal(reports.total, 1);
  await admin.ok(
    `/cms/reports/${reports.items[0].id}`,
    { resolution: 'Проверка завершена, тестовая публикация будет скрыта' },
    'PATCH',
  );
  assert.equal((await admin.ok(`/cms/reports/${reports.items[0].id}`)).status, 'resolved');
  const audit = await admin.ok('/cms/audit?perPage=100');
  assert.ok(audit.total > 0);
  const summary = await admin.ok('/cms/summary');
  assert.ok(summary.news > 0);
  console.log(
    'PASS: CMS scope, search, moderation, editorial revisions, hide/restore, user block/login/session revocation, reports and audit through real HTTP.',
  );
} finally {
  await admin.ok(`/cms/users/${me.id}`, { blocked: false }, 'PATCH');
  await admin.ok(`/cms/news/${created.id}/visibility`, { hidden: true });
}
