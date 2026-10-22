import { useEffect, useState } from 'react';
import { Link } from 'react-router-dom';
import { Alert, Box, Button, Card, CardContent, Skeleton, Stack, Typography } from '@mui/material';
import { ArrowRight, ClipboardCheck, Flag, LibraryBig, Newspaper, UsersRound } from 'lucide-react';
import { Title, useCreatePath } from 'react-admin';
import { api } from '../api/client';
import { useApp } from '../app/context';
import type { CmsSummary } from './contracts';

export function CmsDashboard() {
  const { user, resolvedTheme } = useApp();
  const admin = user?.role === 'ADMIN';
  const createPath = useCreatePath();
  const [summary, setSummary] = useState<CmsSummary>();
  const [userCount, setUserCount] = useState<number>();
  const [error, setError] = useState('');
  const [version, setVersion] = useState(0);
  useEffect(() => {
    let active = true;
    setError('');
    setSummary(undefined);
    setUserCount(undefined);
    Promise.all([
      api<CmsSummary>('/cms/summary'),
      admin ? api<{ total: number }>('/cms/users?page=1&perPage=1') : Promise.resolve(undefined),
    ])
      .then(([counts, users]) => {
        if (active) {
          setSummary(counts);
          setUserCount(users?.total);
        }
      })
      .catch((err) => {
        if (active) setError(err.message);
      });
    return () => {
      active = false;
    };
  }, [admin, user?.id, version]);
  const listPath = (resource: string, status?: string) =>
    createPath({ resource, type: 'list' }) +
    (status ? `?filter=${encodeURIComponent(JSON.stringify({ status }))}` : '');
  const cards = [
    {
      label: 'На проверке',
      value: summary?.pending,
      path: listPath('news', 'pending'),
      icon: ClipboardCheck,
      color: '#7c3aed',
      background: '#f2ebff',
    },
    ...(user?.canModerate
      ? [
          {
            label: 'Открытые жалобы',
            value: summary?.reports,
            path: listPath('reports', 'pending'),
            icon: Flag,
            color: '#b45309',
            background: '#fff4df',
          },
        ]
      : []),
    {
      label: admin ? 'Все публикации' : 'Новости моих пабликов',
      value: summary?.news,
      path: listPath('news'),
      icon: Newspaper,
      color: '#0369a1',
      background: '#eaf5fc',
    },
    {
      label: admin ? 'Паблики' : 'Мои паблики',
      value: summary?.communities,
      path: listPath('communities'),
      icon: LibraryBig,
      color: '#047857',
      background: '#e8f7ef',
    },
    ...(admin
      ? [
          {
            label: 'Пользователи',
            value: userCount,
            path: listPath('users'),
            icon: UsersRound,
            color: '#a21caf',
            background: '#f9edfc',
          },
        ]
      : []),
  ];
  const steps = [
    ['Откройте заявку', 'Во вкладке «Публикации» выберите состояние «На проверке».'],
    ['Проверьте материал', 'Прочитайте текст, откройте источник и просмотрите вложения.'],
    ['Примите решение', 'Одобрите новость или объясните автору, что нужно исправить.'],
  ];
  return (
    <Box sx={{ pb: 4 }}>
      <Title title="Обзор" />
      <Card
        sx={{
          mt: 2,
          mb: 3,
          overflow: 'hidden',
          background: 'linear-gradient(120deg, #26113f, #5b21b6)',
          color: '#fff',
          border: 0,
        }}
      >
        <CardContent
          sx={{
            p: { xs: 3, md: 4 },
            display: 'flex',
            flexWrap: 'wrap',
            gap: 3,
            alignItems: 'center',
            justifyContent: 'space-between',
          }}
        >
          <Box sx={{ maxWidth: 680 }}>
            <Typography
              sx={{
                color: '#d9c8ff',
                fontSize: 12,
                fontWeight: 800,
                letterSpacing: '.14em',
                mb: 1,
              }}
            >
              {admin ? 'ПУЛЬС · УПРАВЛЕНИЕ САЙТОМ' : 'ПУЛЬС · МОДЕРАЦИЯ МОИХ ПАБЛИКОВ'}
            </Typography>
            <Typography component="h1" variant="h4" sx={{ fontWeight: 800, mb: 1 }}>
              Здравствуйте, {user?.name}!
            </Typography>
            <Typography sx={{ color: '#ede5ff', lineHeight: 1.7 }}>
              {admin
                ? 'Проверяйте новости, помогайте авторам и следите за порядком на платформе. Всё необходимое — в одном кабинете.'
                : 'Проверяйте заявки и жалобы своих обычных пабликов. Личные новости и официальные паблики проверяет администрация платформы.'}
            </Typography>
          </Box>
          <Button
            component={Link}
            to={listPath('news', 'pending')}
            variant="contained"
            endIcon={<ArrowRight size={18} />}
            sx={{
              bgcolor: '#d9f99d',
              color: '#26320d',
              '&:hover': { bgcolor: '#bef264' },
              px: 3,
              py: 1.5,
            }}
          >
            Проверить заявки
          </Button>
        </CardContent>
      </Card>
      {error && (
        <Alert
          severity="error"
          sx={{ mb: 3 }}
          action={
            <Button color="inherit" onClick={() => setVersion((old) => old + 1)}>
              Повторить
            </Button>
          }
        >
          {error}
        </Alert>
      )}
      <Box
        sx={{
          display: 'grid',
          gap: 2,
          gridTemplateColumns: { xs: '1fr 1fr', md: `repeat(${cards.length}, minmax(0, 1fr))` },
          mb: 3,
        }}
      >
        {cards.map(({ label, value, path, icon: Icon, color, background }) => (
          <Card
            key={label}
            component={Link}
            to={path}
            sx={{
              textDecoration: 'none',
              color: 'inherit',
              transition: 'transform .15s, border-color .15s',
              '&:hover': { transform: 'translateY(-2px)', borderColor: color },
            }}
          >
            <CardContent>
              <Box
                sx={{
                  width: 40,
                  height: 40,
                  borderRadius: 2,
                  display: 'grid',
                  placeItems: 'center',
                  bgcolor: resolvedTheme === 'dark' ? 'action.hover' : background,
                  color: resolvedTheme === 'dark' ? 'primary.main' : color,
                  mb: 2,
                }}
              >
                <Icon size={21} />
              </Box>
              <Typography variant="h4" sx={{ fontWeight: 800 }}>
                {value === undefined ? <Skeleton width={50} /> : value.toLocaleString('ru')}
              </Typography>
              <Typography color="text.secondary" sx={{ fontSize: 13, mt: 1 }}>
                {label}
              </Typography>
            </CardContent>
          </Card>
        ))}
      </Box>
      <Box sx={{ display: 'grid', gridTemplateColumns: { xs: '1fr', md: '1.25fr 1fr' }, gap: 3 }}>
        <Card>
          <CardContent sx={{ p: 3 }}>
            <Typography variant="h6" sx={{ mb: 2, fontWeight: 800 }}>
              Как выпустить новость
            </Typography>
            <Stack spacing={2.5}>
              {steps.map(([title, text], index) => (
                <Stack direction="row" spacing={2} key={title}>
                  <Box
                    sx={{
                      flexShrink: 0,
                      width: 30,
                      height: 30,
                      borderRadius: '50%',
                      bgcolor: 'action.hover',
                      color: 'primary.main',
                      fontWeight: 800,
                      display: 'grid',
                      placeItems: 'center',
                    }}
                  >
                    {index + 1}
                  </Box>
                  <Box>
                    <Typography sx={{ fontWeight: 700 }}>{title}</Typography>
                    <Typography variant="body2" color="text.secondary" sx={{ mt: 0.5 }}>
                      {text}
                    </Typography>
                  </Box>
                </Stack>
              ))}
            </Stack>
          </CardContent>
        </Card>
        <Card>
          <CardContent sx={{ p: 3 }}>
            <Typography variant="h6" sx={{ mb: 2, fontWeight: 800 }}>
              Под рукой
            </Typography>
            <Stack spacing={1} sx={{ alignItems: 'flex-start' }}>
              <Button
                component="a"
                href={admin ? '/create' : '/communities'}
                endIcon={<ArrowRight size={17} />}
              >
                {admin ? 'Предложить свою новость' : 'Предложить новость в паблик'}
              </Button>
              <Button
                component={Link}
                to={listPath('communities')}
                endIcon={<ArrowRight size={17} />}
              >
                Настроить паблики
              </Button>
              {user?.canModerate && (
                <Button
                  component={Link}
                  to={listPath('reports', 'pending')}
                  endIcon={<ArrowRight size={17} />}
                >
                  Рассмотреть жалобы
                </Button>
              )}
              <Button component="a" href="/" endIcon={<ArrowRight size={17} />}>
                Открыть сайт для читателей
              </Button>
            </Stack>
            <Alert severity="info" sx={{ mt: 2 }}>
              После редактирования новость снова проходит проверку. Старая одобренная версия
              остаётся у читателей до принятия решения.
            </Alert>
          </CardContent>
        </Card>
      </Box>
    </Box>
  );
}
