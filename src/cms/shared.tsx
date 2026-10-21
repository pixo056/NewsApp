import { useState } from 'react';
import type { ReactNode } from 'react';
import {
  Alert,
  Box,
  Button,
  Chip,
  Dialog,
  DialogActions,
  DialogContent,
  DialogContentText,
  DialogTitle,
  TextField as MuiTextField,
  Typography,
} from '@mui/material';
import { useNotify, useRecordContext, useRefresh } from 'react-admin';
import { json } from '../api/client';
import type { CmsNews } from './contracts';

export const dateOptions: Intl.DateTimeFormatOptions = {
  day: 'numeric',
  month: 'short',
  year: 'numeric',
  hour: '2-digit',
  minute: '2-digit',
  timeZone: 'Asia/Yekaterinburg',
};

export function EmptySection({ title, text }: { title: string; text: string }) {
  return (
    <Box sx={{ p: { xs: 3, md: 6 }, textAlign: 'center' }}>
      <Typography variant="h6" gutterBottom>
        {title}
      </Typography>
      <Typography color="text.secondary">{text}</Typography>
    </Box>
  );
}

export function RecordTitle({ field = 'title', fallback }: { field?: string; fallback: string }) {
  const record = useRecordContext();
  return <span>{record?.[field] || fallback}</span>;
}

export function NewsStatus({ record: supplied }: { record?: CmsNews }) {
  const context = useRecordContext<CmsNews>();
  const record = supplied || context;
  if (!record) return null;
  if (record.deleted) return <Chip size="small" label="Скрыта" variant="outlined" />;
  if (record.hasPendingChanges)
    return <Chip size="small" label="Правки на проверке" color="warning" />;
  const statuses = {
    pending: ['На проверке', 'warning'],
    published: ['Опубликована', 'success'],
    rejected: ['Отклонена', 'error'],
  } as const;
  const [label, color] = statuses[record.status] || ['Заявка заменена', 'default'];
  return <Chip size="small" label={label} color={color} />;
}

export function NewsMedia() {
  const record = useRecordContext<CmsNews>();
  if (!record?.media?.length) return null;
  return (
    <Box
      sx={{ display: 'grid', gap: 2, gridTemplateColumns: 'repeat(auto-fit, minmax(220px, 1fr))' }}
    >
      {record.media.map((asset) =>
        asset.kind === 'image' ? (
          <Box
            component="img"
            key={asset.id}
            src={asset.url}
            alt="Вложение новости"
            sx={{
              width: '100%',
              maxHeight: 420,
              objectFit: 'contain',
              borderRadius: 2,
              bgcolor: '#f5f3f8',
            }}
          />
        ) : (
          <Box
            component="video"
            key={asset.id}
            src={asset.url}
            poster={asset.posterUrl}
            controls
            preload="none"
            playsInline
            sx={{ width: '100%', maxHeight: 420, borderRadius: 2, bgcolor: '#15101e' }}
          />
        ),
      )}
    </Box>
  );
}

export function ConfirmAction({
  label,
  title,
  description,
  request,
  success,
  color = 'primary',
  reason,
  icon,
  disabled = false,
}: {
  label: string;
  title: string;
  description: string;
  request: (reason: string) => Promise<unknown>;
  success: string;
  color?: 'primary' | 'error' | 'success';
  reason?: { label: string; min: number; max: number; helper: string };
  icon?: ReactNode;
  disabled?: boolean;
}) {
  const [open, setOpen] = useState(false);
  const [value, setValue] = useState('');
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState('');
  const notify = useNotify();
  const refresh = useRefresh();
  const invalidReason = !!reason && (value.trim().length < reason.min || value.length > reason.max);
  return (
    <>
      <Button
        size="small"
        color={color}
        startIcon={icon}
        disabled={disabled}
        onClick={() => {
          setValue('');
          setError('');
          setOpen(true);
        }}
      >
        {label}
      </Button>
      <Dialog
        open={open}
        onClose={() => {
          if (!busy) setOpen(false);
        }}
        fullWidth
        maxWidth="sm"
      >
        <DialogTitle>{title}</DialogTitle>
        <DialogContent>
          <DialogContentText>{description}</DialogContentText>
          {reason && (
            <MuiTextField
              autoFocus
              fullWidth
              multiline
              minRows={3}
              margin="normal"
              label={reason.label}
              value={value}
              onChange={(event) => setValue(event.target.value)}
              helperText={reason.helper}
              slotProps={{ htmlInput: { maxLength: reason.max } }}
              disabled={busy}
            />
          )}
          {error && (
            <Alert severity="error" sx={{ mt: 2 }}>
              {error}
            </Alert>
          )}
        </DialogContent>
        <DialogActions sx={{ px: 3, pb: 2 }}>
          <Button disabled={busy} onClick={() => setOpen(false)}>
            Отмена
          </Button>
          <Button
            variant="contained"
            color={color}
            disabled={busy || invalidReason}
            onClick={async () => {
              setBusy(true);
              setError('');
              try {
                await request(value.trim());
                notify(success, { type: 'success', messageArgs: { _: success } });
                setOpen(false);
                refresh();
              } catch (err) {
                setError((err as Error).message);
              } finally {
                setBusy(false);
              }
            }}
          >
            {busy ? 'Сохраняем…' : label}
          </Button>
        </DialogActions>
      </Dialog>
    </>
  );
}

export function VisibilityAction({ newsId, hidden = false }: { newsId: string; hidden?: boolean }) {
  return (
    <ConfirmAction
      label={hidden ? 'Вернуть публикацию' : 'Скрыть новость'}
      color={hidden ? 'primary' : 'error'}
      title={hidden ? 'Вернуть публикацию?' : 'Скрыть новость из ленты?'}
      description={
        hidden
          ? 'Одобренная версия снова станет доступна читателям. Если новость ещё не одобрена, она останется на проверке.'
          : 'Новость исчезнет из общей ленты и сохранённого. Её история останется, и публикацию можно будет вернуть.'
      }
      request={() => json(`/cms/news/${newsId}/visibility`, { hidden: !hidden })}
      success={hidden ? 'Публикация восстановлена' : 'Новость скрыта из ленты'}
    />
  );
}
