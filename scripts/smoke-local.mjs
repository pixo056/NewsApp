import assert from 'node:assert/strict';
import { readFile } from 'node:fs/promises';
import { randomUUID } from 'node:crypto';

const base = 'http://localhost:5173';
const config = JSON.parse(
  await readFile(new URL('../.local/config.json', import.meta.url), 'utf8'),
);
class Client {
  cookies = new Map();
  async request(path, { method = 'GET', body, form = false } = {}) {
    const headers = {};
    if (method !== 'GET') {
      const response = await this.request('/auth/csrf');
      assert.equal(response.status, 200);
      headers[response.data.headerName] = response.data.token;
    }
    if (this.cookies.size)
      headers.Cookie = [...this.cookies].map(([key, value]) => `${key}=${value}`).join('; ');
    if (body && !(body instanceof FormData)) {
      headers['Content-Type'] = form ? 'application/x-www-form-urlencoded' : 'application/json';
      body = form ? new URLSearchParams(body) : JSON.stringify(body);
    }
    const response = await fetch(`${base}/api${path}`, { method, headers, body });
    for (const cookie of response.headers.getSetCookie()) {
      const first = cookie.split(';')[0];
      const index = first.indexOf('=');
      this.cookies.set(first.slice(0, index), first.slice(index + 1));
    }
    const text = await response.text();
    let data;
    try {
      data = text ? JSON.parse(text) : undefined;
    } catch {
      data = { raw: text.slice(0, 100) };
    }
    return { status: response.status, data, type: response.headers.get('content-type') };
  }
  async ok(path, options) {
    const result = await this.request(path, options);
    assert.ok(
      result.status >= 200 && result.status < 300,
      `${path}: HTTP ${result.status}, ${result.data?.message || ''}`,
    );
    return result.data;
  }
  async login(username, password) {
    return this.ok('/auth/login', { method: 'POST', form: true, body: { username, password } });
  }
}
const guest = new Client(),
  admin = new Client(),
  owner = new Client(),
  student = new Client();
const nonce = randomUUID().slice(0, 8),
  username = `smoke.${nonce}`,
  email = `${username}@local.test`,
  password = `LocalTest!${nonce}`;
const initial = await guest.ok('/auth/me');
assert.equal(initial.localMailbox, true);
assert.equal((await fetch(`${base}/.local/config.json`)).status, 403);
await admin.login('uunit.admin', config.adminPassword);
await owner.login('club.owner', config.ownerPassword);
await guest.ok('/auth/register', {
  method: 'POST',
  body: { username, email, name: 'Проверка полного сценария', password },
});
await student.login(username, password);
assert.equal((await student.ok('/auth/me')).user.verified, false);
const letters = await guest.ok('/dev/mailbox');
const verify = letters.find((mail) => mail.recipient === email).actionUrl.split('token=')[1];
await student.ok('/auth/verify-email', { method: 'POST', body: { token: verify } });
assert.equal((await student.ok('/auth/me')).user.verified, true);
const groups = await guest.ok('/communities');
const club = groups.find((c) => c.name === 'IT-сообщество');
const upload = new FormData();
upload.set(
  'file',
  new Blob([await readFile(new URL('../public/images/campus.jpg', import.meta.url))], {
    type: 'image/jpeg',
  }),
  'campus.jpg',
);
const asset = await student.ok('/media', { method: 'POST', body: upload });
assert.equal((await guest.request(`/media/${asset.id}`)).status, 404);
const input = {
  title: `Проверка новостей ${nonce}`,
  text: 'Полезная новость для проверки настоящего пользовательского сценария от регистрации до модерации и сохранения в базе.',
  category: 'Технологии',
  communityId: club.id,
  source: 'https://uust.ru/',
  mediaIds: [asset.id],
};
const created = await student.ok('/news', { method: 'POST', body: input });
assert.equal((await guest.request(`/news/${created.id}`)).status, 404);
let item = (await admin.ok('/admin/revisions')).find((p) => p.id === created.id);
assert.ok(item);
assert.equal(
  (
    await student.request(`/admin/revisions/${item.revisionId}/approve`, {
      method: 'POST',
      body: {},
    })
  ).status,
  403,
);
assert.equal(
  (await owner.request(`/admin/revisions/${item.revisionId}/approve`, { method: 'POST', body: {} }))
    .status,
  200,
);
assert.equal((await owner.request(`/cms/news/${created.id}`)).status, 200);
assert.equal((await guest.ok(`/news/${created.id}`)).title, input.title);
assert.equal((await guest.ok(`/news?q=${nonce}`)).items.length, 0);
assert.equal(
  (
    await owner.request(`/admin/revisions/${item.revisionId}/main/approve`, {
      method: 'POST',
      body: {},
    })
  ).status,
  403,
);
await admin.ok(`/admin/revisions/${item.revisionId}/main/approve`, { method: 'POST', body: {} });
assert.equal((await guest.ok(`/news?q=${nonce}`)).items.length, 1);
const firstView = await guest.ok(`/news/${created.id}/view`, { method: 'POST', body: {} });
assert.deepEqual(firstView, { views: 1, counted: true });
assert.deepEqual(await guest.ok(`/news/${created.id}/view`, { method: 'POST', body: {} }), {
  views: 1,
  counted: false,
});
const anotherGuest = new Client();
assert.deepEqual(await anotherGuest.ok(`/news/${created.id}/view`, { method: 'POST', body: {} }), {
  views: 2,
  counted: true,
});
assert.deepEqual(await student.ok(`/news/${created.id}/view`, { method: 'POST', body: {} }), {
  views: 3,
  counted: true,
});
await student.ok(`/communities/${club.id}/subscription`, { method: 'PUT' });
await student.ok(`/news/${created.id}/bookmark`, { method: 'PUT' });
await student.ok(`/news/${created.id}/reaction`, { method: 'PUT' });
assert.equal((await student.ok(`/news/${created.id}/reactions`)).selected, 'like');
await student.ok(`/news/${created.id}/reaction`, { method: 'PUT', body: { kind: 'insightful' } });
assert.deepEqual(await student.ok(`/news/${created.id}/reactions`), {
  counts: { like: 0, celebrate: 0, insightful: 1, support: 0 },
  selected: 'insightful',
  total: 1,
});
assert.equal((await guest.ok(`/news/${created.id}`)).reactionCounts.insightful, 1);
assert.equal((await student.ok(`/news/${created.id}`)).myReaction, 'insightful');
assert.ok((await student.ok('/me/preferences')).saved.includes(created.id));
await student.ok('/auth/logout', { method: 'POST', body: {} });
await student.login(username, password);
assert.deepEqual(await student.ok(`/news/${created.id}/view`, { method: 'POST', body: {} }), {
  views: 3,
  counted: false,
});
assert.ok((await student.ok('/me/preferences')).followed.includes(club.id));
assert.equal((await student.ok(`/news/${created.id}/reactions`)).selected, 'insightful');
const edited = { ...input, title: `Изменённая новость ${nonce}` };
await student.ok(`/news/${created.id}/revisions`, { method: 'POST', body: edited });
assert.equal((await guest.ok(`/news/${created.id}`)).title, input.title);
item = (await admin.ok('/admin/revisions')).find((p) => p.id === created.id);
await admin.ok(`/admin/revisions/${item.revisionId}/reject`, {
  method: 'POST',
  body: { reason: 'Дополните материал подробностями события' },
});
assert.equal((await guest.ok(`/news/${created.id}`)).title, input.title);
await student.ok(`/news/${created.id}/revisions`, { method: 'POST', body: edited });
item = (await admin.ok('/admin/revisions')).find((p) => p.id === created.id);
await admin.ok(`/admin/revisions/${item.revisionId}/approve`, { method: 'POST', body: {} });
assert.equal((await guest.ok(`/news/${created.id}`)).title, edited.title);
await admin.ok(`/admin/revisions/${item.revisionId}/main/approve`, { method: 'POST', body: {} });
assert.equal((await guest.ok(`/news?q=${nonce}`)).items.length, 1);
assert.ok((await student.ok('/me/notifications')).length >= 3);
await student.ok(`/news/${created.id}/reports`, {
  method: 'POST',
  body: { reason: 'Проверка модерации', comment: 'Локальный автоматический тест' },
});
const report = (await admin.ok('/admin/reports')).find((r) => r.newsId === created.id);
assert.ok(report);
await admin.ok(`/admin/news/${created.id}/hide`, { method: 'POST', body: {} });
await admin.ok(`/admin/reports/${report.id}`, {
  method: 'PATCH',
  body: { resolution: 'Автоматический тест завершён, тестовая новость скрыта' },
});
assert.equal((await guest.request(`/news/${created.id}`)).status, 404);
assert.equal(
  (await guest.request(`/news/${created.id}/view`, { method: 'POST', body: {} })).status,
  404,
);
assert.ok(!(await student.ok('/news?saved=true')).items.some((p) => p.id === created.id));
await guest.ok('/auth/forgot-password', { method: 'POST', body: { email } });
const reset = (await guest.ok('/dev/mailbox'))
  .find((m) => m.recipient === email && m.subject.includes('Восстановление'))
  .actionUrl.split('token=')[1];
await guest.ok('/auth/reset-password', {
  method: 'POST',
  body: { token: reset, password: `NewLocalTest!${nonce}` },
});
assert.equal((await student.request('/auth/me')).status, 401);
await student.login(username, `NewLocalTest!${nonce}`);
assert.equal((await student.ok('/auth/me')).user.username, username);
assert.equal(
  (
    await guest.request('/auth/reset-password', {
      method: 'POST',
      body: { token: reset, password: 'AnotherLocalPass!123' },
    })
  ).status,
  400,
);
console.log(
  'PASS: real HTTP registration, CSRF, verification, login, upload, ADMIN premoderation, owner restrictions, deduplicated views, revisions, persistence, search, emoji reactions, reports, password reset and session invalidation.',
);
