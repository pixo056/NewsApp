import assert from 'node:assert/strict';
import test from 'node:test';
import { cmsListQuery, cmsUpdatePayload, createCmsDataProvider } from '../src/cms/dataProvider.ts';

test('CMS query preserves server pagination, sorting and explicit false filters', () => {
  const params = new URLSearchParams(
    cmsListQuery({
      pagination: { page: 3, perPage: 25 },
      sort: { field: 'name', order: 'ASC' },
      filter: { q: 'спорт & наука', blocked: false, status: '', role: 'USER', ignored: 'private' },
    }),
  );
  assert.equal(params.get('page'), '3');
  assert.equal(params.get('perPage'), '25');
  assert.equal(params.get('sort'), 'name');
  assert.equal(params.get('order'), 'ASC');
  assert.equal(params.get('q'), 'спорт & наука');
  assert.equal(params.get('blocked'), 'false');
  assert.equal(params.get('role'), 'USER');
  assert.equal(params.has('status'), false);
  assert.equal(params.has('ignored'), false);
});

test('editing news preserves its personal author scope and attachments without copying private fields', () => {
  const previous = {
    id: 'news-1',
    title: 'Старый заголовок',
    text: 'Текст',
    category: 'Спорт',
    communityId: null,
    source: '',
    media: [{ id: 'photo-1' }],
    authorId: 'author-1',
    deleted: false,
  };
  const payload = cmsUpdatePayload(
    'news',
    { title: 'Новый заголовок', source: ' https://example.org/ ', communityId: 'other-community' },
    previous,
  );
  assert.deepEqual(payload, {
    title: 'Новый заголовок',
    text: 'Текст',
    category: 'Спорт',
    communityId: null,
    source: 'https://example.org/',
    mediaIds: ['photo-1'],
  });
  assert.equal(previous.title, 'Старый заголовок');
  assert.equal('authorId' in payload, false);
  assert.equal('deleted' in payload, false);
});

test('resource updates send only changed editable fields and never alter user roles or access implicitly', () => {
  assert.deepEqual(
    cmsUpdatePayload(
      'communities',
      {
        name: 'Новое имя',
        description: 'Описание',
        dailyLimit: 5,
        official: false,
        ownerId: 'attacker',
      },
      { name: 'Старое имя', description: 'Описание', dailyLimit: 5, official: false },
    ),
    { name: 'Новое имя' },
  );
  assert.deepEqual(
    cmsUpdatePayload('users', {
      name: 'Иван',
      bio: 'Студент',
      role: 'ADMIN',
      blocked: true,
      password: 'secret',
    }),
    {
      name: 'Иван',
      bio: 'Студент',
    },
  );
  assert.deepEqual(
    cmsUpdatePayload('reports', {
      resolution: 'Рассмотрено',
      status: 'resolved',
      reporter: 'changed',
    }),
    { resolution: 'Рассмотрено' },
  );
  assert.throws(() => cmsUpdatePayload('audit', { detail: 'edited' }));
});

test('CMS provider uses total from API and reads back the confirmed record after a patch', async () => {
  const requests: { path: string; options?: RequestInit }[] = [];
  const request = async <T>(path: string, options?: RequestInit): Promise<T> => {
    requests.push({ path, options });
    if (path.includes('?'))
      return { items: [{ id: 'community-1', name: 'Паблик' }], total: 42 } as T;
    return { id: 'community-1', name: 'Обновлённый паблик', official: false } as T;
  };
  const provider = createCmsDataProvider(request);
  const page = await provider.getList('communities', {
    pagination: { page: 1, perPage: 25 },
    sort: { field: 'name', order: 'ASC' },
    filter: {},
  });
  assert.equal(page.total, 42);
  const updated = await provider.update('communities', {
    id: 'community-1',
    data: { name: 'Обновлённый паблик', official: false },
    previousData: { id: 'community-1', name: 'Паблик', official: false },
  });
  assert.equal(requests[1].options?.method, 'PATCH');
  assert.deepEqual(JSON.parse(String(requests[1].options?.body)), { name: 'Обновлённый паблик' });
  assert.equal(requests[2].options, undefined);
  assert.equal(updated.data.name, 'Обновлённый паблик');
});

test('failed CMS mutations remain errors and unsupported destructive actions never make requests', async () => {
  let calls = 0;
  const provider = createCmsDataProvider(async () => {
    calls++;
    throw new Error('Нет доступа');
  });
  await assert.rejects(
    provider.update('communities', {
      id: '1',
      data: { name: 'Название' },
      previousData: { id: '1' },
    }),
    /Нет доступа/,
  );
  assert.equal(calls, 1);
  await assert.rejects(provider.delete('news', { id: '1' }));
  await assert.rejects(provider.deleteMany('users', { ids: ['1'] }));
  assert.equal(calls, 1);
  await assert.rejects(
    provider.getList('secrets', {
      pagination: { page: 1, perPage: 25 },
      sort: { field: 'id', order: 'ASC' },
      filter: {},
    }),
  );
  assert.equal(calls, 1);
});
