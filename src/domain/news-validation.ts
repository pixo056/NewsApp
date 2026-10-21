export type NewsDraft = {
  title: string;
  text: string;
  source: string;
  hasVideo: boolean;
};

export type NewsValidationErrors = Partial<Record<'title' | 'text' | 'source', string>>;

export function validateNewsMedia(
  files: readonly { name: string; size: number }[],
  existing: readonly { kind: 'image' | 'video' }[],
): string | undefined {
  const incomingVideo = files.some((file) => /\.(mp4|mov)$/i.test(file.name));
  if (
    (incomingVideo && (files.length !== 1 || existing.length > 0)) ||
    (existing.some((asset) => asset.kind === 'video') && files.length > 0) ||
    (!incomingVideo && existing.length + files.length > 4)
  )
    return 'Прикрепите до четырёх фото либо одно видео.';
  for (const file of files) {
    if (!file.size) return `Файл «${file.name}» пуст. Выберите другой файл.`;
    const isVideo = /\.(mp4|mov)$/i.test(file.name);
    const limitMiB = isVideo ? 200 : 10;
    if (file.size > limitMiB * 1024 * 1024)
      return `Файл «${file.name}» превышает ${limitMiB} МиБ. Выберите файл меньшего размера.`;
  }
}

export function validateNewsDraft(draft: NewsDraft): NewsValidationErrors {
  const errors: NewsValidationErrors = {};
  const titleLength = Array.from(draft.title).length;
  const textLength = Array.from(draft.text).length;
  if (titleLength < 10 || titleLength > 150 || !draft.title.trim())
    errors.title = 'Заголовок должен содержать от 10 до 150 символов с пробелами.';
  if (textLength > 10000 || (!draft.hasVideo && textLength < 50))
    errors.text = 'Текст должен содержать от 50 до 10 000 символов. С видео текст необязателен.';
  const source = draft.source.trim();
  if (source) {
    try {
      const url = new URL(source);
      if (
        source.length > 2000 ||
        !/^https?:\/\//.test(source) ||
        !['http:', 'https:'].includes(url.protocol) ||
        !url.hostname ||
        /\s/.test(source)
      )
        throw new Error('Invalid source');
    } catch {
      errors.source = 'Укажите корректную ссылку на источник, начинающуюся с http:// или https://.';
    }
  }
  return errors;
}
