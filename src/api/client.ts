import type { MediaAsset } from '../domain/types';
const base = import.meta.env?.VITE_API_URL || '/api';
const connectionError = 'Нет связи с локальным сервером. Проверьте, что start.cmd работает.';
const unexpectedResponse = 'Сервер вернул неожиданный ответ. Проверьте backend.';
export class ApiError extends Error {
  status: number;
  constructor(status: number, message: string) {
    super(message);
    this.status = status;
  }
}
export async function csrf() {
  let response: Response;
  try {
    response = await fetch(`${base}/auth/csrf`, { credentials: 'include', cache: 'no-store' });
  } catch {
    throw new ApiError(0, connectionError);
  }
  if (!response.ok) throw new ApiError(response.status, 'Backend недоступен. Запустите start.cmd.');
  try {
    const data: unknown = await response.json();
    if (
      !data ||
      typeof data !== 'object' ||
      !('token' in data) ||
      typeof data.token !== 'string' ||
      !data.token ||
      !('headerName' in data) ||
      typeof data.headerName !== 'string' ||
      !data.headerName
    )
      throw new Error('Invalid CSRF response');
    return { token: data.token, headerName: data.headerName };
  } catch {
    throw new ApiError(response.status, unexpectedResponse);
  }
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
    throw new ApiError(0, connectionError);
  }
  const text = await response.text();
  let data: { message?: string } = {};
  if (text) {
    try {
      data = JSON.parse(text);
    } catch {
      throw new ApiError(response.status, unexpectedResponse);
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
