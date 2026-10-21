import type { MediaAsset } from '../domain/types';
const base = import.meta.env.VITE_API_URL || '/api';
export class ApiError extends Error {
  constructor(
    public status: number,
    message: string,
  ) {
    super(message);
  }
}
export async function csrf() {
  const response = await fetch(`${base}/auth/csrf`, { credentials: 'include', cache: 'no-store' });
  if (!response.ok) throw new ApiError(response.status, 'Backend недоступен. Запустите start.cmd.');
  return response.json() as Promise<{ token: string; headerName: string }>;
}
export async function api<T = void>(path: string, options: RequestInit = {}): Promise<T> {
  const method = options.method || 'GET';
  const headers = new Headers(options.headers);
  if (!['GET', 'HEAD'].includes(method)) {
    const token = await csrf();
    headers.set(token.headerName, token.token);
  }
  if (options.body && typeof options.body === 'string' && !headers.has('Content-Type'))
    headers.set('Content-Type', 'application/json');
  let response: Response;
  try {
    response = await fetch(`${base}${path}`, {
      ...options,
      headers,
      credentials: 'include',
      cache: 'no-store',
    });
  } catch {
    throw new ApiError(0, 'Нет связи с локальным сервером. Проверьте, что start.cmd работает.');
  }
  const text = await response.text();
  let data: { message?: string } = {};
  if (text) {
    try {
      data = JSON.parse(text);
    } catch {
      throw new ApiError(response.status, 'Сервер вернул неожиданный ответ. Проверьте backend.');
    }
  }
  if (!response.ok)
    throw new ApiError(
      response.status,
      data.message ||
        (response.status === 401 ? 'Войдите в аккаунт' : 'Не удалось выполнить запрос'),
    );
  return data as T;
}
export function json<T = void>(path: string, body: unknown, method = 'POST') {
  return api<T>(path, { method, body: JSON.stringify(body) });
}
export async function upload(file: File, progress: (value: number) => void): Promise<MediaAsset> {
  const token = await csrf();
  return new Promise((resolve, reject) => {
    const xhr = new XMLHttpRequest();
    xhr.open('POST', `${base}/media`);
    xhr.withCredentials = true;
    xhr.setRequestHeader(token.headerName, token.token);
    xhr.upload.onprogress = (e) => {
      if (e.lengthComputable) progress(Math.round((e.loaded / e.total) * 100));
    };
    xhr.onerror = () => reject(new ApiError(0, 'Не удалось загрузить файл. Проверьте соединение.'));
    xhr.onload = () => {
      try {
        const data = JSON.parse(xhr.responseText);
        if (xhr.status >= 200 && xhr.status < 300) resolve(data);
        else reject(new ApiError(xhr.status, data.message || 'Ошибка загрузки'));
      } catch {
        reject(new ApiError(xhr.status, 'Некорректный ответ при загрузке'));
      }
    };
    const form = new FormData();
    form.append('file', file);
    xhr.send(form);
  });
}
