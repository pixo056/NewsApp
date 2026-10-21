import assert from 'node:assert/strict';
import test from 'node:test';
import { validateNewsDraft, validateNewsMedia } from '../src/domain/news-validation.ts';
import { api, ApiError, csrf, upload } from '../src/api/client.ts';
import { authReturnTo } from '../src/domain/auth-return.ts';

test('sign-in returns to the selected community submission without allowing external redirects', () => {
  const id = 'a3e14066-c7ea-4be1-a0dd-52ad72c5ee11';
  for (const path of [
    '/cms',
    '/create',
    `/communities/${id}/submit`,
    `/create?community=${id}`,
    `/news/${id}`,
  ]) {
    assert.equal(authReturnTo(path), path);
  }
  for (const path of [
    null,
    '',
    'https://example.com',
    '//example.com',
    '/\\example.com',
    '/cms/../other',
    `/communities/${id}/submit?redirect=https://example.com`,
    '/communities/unknown/submit',
    `/create?community=${id}&redirect=https://example.com`,
  ]) {
    assert.equal(authReturnTo(path), '/profile');
  }
});

const draft = { title: 'Название новости', text: 'Т'.repeat(50), source: '', hasVideo: false };

test('media selection rejects oversized files before upload and permits exact image/video limits', () => {
  for (const [name, limitMiB] of [
    ['photo.jpg', 10],
    ['photo.webp', 10],
    ['clip.mp4', 200],
    ['clip.MOV', 200],
  ] as const) {
    const size = limitMiB * 1024 * 1024;
    assert.equal(validateNewsMedia([{ name, size }], []), undefined);
    assert.match(
      validateNewsMedia([{ name, size: size + 1 }], []) || '',
      new RegExp(`${limitMiB} МиБ`),
    );
    assert.ok(validateNewsMedia([{ name, size: 0 }], []));
  }
});

test('media selection allows four photos or one video without mixed attachments', () => {
  const photo = { name: 'photo.jpg', size: 100 };
  const video = { name: 'clip.mp4', size: 100 };
  assert.equal(validateNewsMedia([photo, photo, photo, photo], []), undefined);
  assert.equal(validateNewsMedia([video], []), undefined);
  assert.ok(validateNewsMedia([photo, photo, photo, photo, photo], []));
  assert.ok(validateNewsMedia([photo, video], []));
  assert.ok(validateNewsMedia([video], [{ kind: 'image' }]));
  assert.ok(validateNewsMedia([photo], [{ kind: 'video' }]));
  assert.ok(
    validateNewsMedia([photo, photo], [{ kind: 'image' }, { kind: 'image' }, { kind: 'image' }]),
  );
});

test('news title limits count Unicode code points, including supplementary characters', () => {
  for (const character of ['Т', '😀']) {
    for (const length of [10, 150])
      assert.deepEqual(validateNewsDraft({ ...draft, title: character.repeat(length) }), {});
    for (const length of [9, 151])
      assert.ok(validateNewsDraft({ ...draft, title: character.repeat(length) }).title);
  }
  assert.ok(validateNewsDraft({ ...draft, title: ' '.repeat(10) }).title);
  assert.equal(validateNewsDraft({ ...draft, title: 'Заголовок ' }).title, undefined);
});

test('body limits include spaces and permit optional video text up to the same maximum', () => {
  for (const character of ['Т', '😀', ' ']) {
    for (const length of [50, 10000])
      assert.equal(validateNewsDraft({ ...draft, text: character.repeat(length) }).text, undefined);
    for (const length of [49, 10001])
      assert.ok(validateNewsDraft({ ...draft, text: character.repeat(length) }).text);
  }
  assert.ok(validateNewsDraft({ ...draft, text: '' }).text);
  for (const text of ['', 'Короткий текст', '😀'.repeat(10000)])
    assert.equal(validateNewsDraft({ ...draft, text, hasVideo: true }).text, undefined);
  assert.ok(validateNewsDraft({ ...draft, text: '😀'.repeat(10001), hasVideo: true }).text);
});

test('sources are optional and accept only complete HTTP or HTTPS URLs', () => {
  for (const source of ['', '  ', 'https://example.org/news', ' http://example.org/a?b=1 '])
    assert.equal(validateNewsDraft({ ...draft, source }).source, undefined);
  for (const source of [
    'javascript:alert(1)',
    'ftp://example.org/news',
    'example.org/news',
    'https:example.org',
    'https://',
    'https://example.org/a b',
    `https://example.org/${'a'.repeat(2000)}`,
  ])
    assert.ok(validateNewsDraft({ ...draft, source }).source, source);
});

test('offline CSRF errors are actionable for direct requests, mutations and uploads', async (t) => {
  t.mock.method(globalThis, 'fetch', async () => {
    throw new TypeError('Failed to fetch');
  });
  const expected = (error: unknown) =>
    error instanceof ApiError &&
    error.status === 0 &&
    error.message === 'Нет связи с локальным сервером. Проверьте, что start.cmd работает.';
  await assert.rejects(csrf(), expected);
  await assert.rejects(api('/news', { method: 'POST', body: '{}' }), expected);
  await assert.rejects(
    upload(new File(['image'], 'photo.jpg'), () => {}),
    expected,
  );
});

test('CSRF rejects unavailable, malformed and incomplete responses without leaking parser errors', async (t) => {
  const fetchMock = t.mock.method(
    globalThis,
    'fetch',
    async () => new Response('', { status: 503 }),
  );
  await assert.rejects(
    csrf(),
    (error: unknown) => error instanceof ApiError && error.status === 503,
  );
  for (const body of [
    '<html>Proxy error</html>',
    '{}',
    'null',
    '{"token":42,"headerName":"X-CSRF-TOKEN"}',
  ]) {
    fetchMock.mock.mockImplementation(async () => new Response(body));
    await assert.rejects(
      csrf(),
      (error: unknown) =>
        error instanceof ApiError &&
        error.message === 'Сервер вернул неожиданный ответ. Проверьте backend.',
    );
  }
});

test('valid CSRF tokens are passed to the modifying request', async (t) => {
  const requests: [string, RequestInit | undefined][] = [];
  t.mock.method(globalThis, 'fetch', async (url: string, options?: RequestInit) => {
    requests.push([url, options]);
    return new Response(
      requests.length === 1
        ? JSON.stringify({ token: 'test-token', headerName: 'X-CSRF-TOKEN' })
        : JSON.stringify({ id: 'created-news' }),
    );
  });
  assert.deepEqual(await api('/news', { method: 'POST', body: '{}' }), { id: 'created-news' });
  assert.equal(requests[0][0], '/api/auth/csrf');
  assert.equal(requests[1][0], '/api/news');
  assert.equal(new Headers(requests[1][1]?.headers).get('X-CSRF-TOKEN'), 'test-token');
  assert.equal(requests[1][1]?.credentials, 'include');
});
