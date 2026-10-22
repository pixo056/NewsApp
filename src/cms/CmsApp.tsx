import { Box, Button, Typography } from '@mui/material';
import { ArrowLeft, Flag, History, LibraryBig, Newspaper, UsersRound } from 'lucide-react';
import {
  Admin,
  AppBar,
  Layout,
  Resource,
  TitlePortal,
  defaultTheme,
  type AppBarProps,
  type LayoutProps,
} from 'react-admin';
import polyglotI18nProvider from 'ra-i18n-polyglot';
import russianMessages from 'ra-language-russian';
import { useApp } from '../app/context';
import { createCmsAuthProvider } from './authProvider';
import { cmsDataProvider } from './dataProvider';
import { CmsDashboard } from './Dashboard';
import { NewsEdit, NewsList, NewsShow } from './News';
import { CommunityEdit, CommunityList, CommunityShow } from './Communities';
import { ReportEdit, ReportList, ReportShow } from './Reports';
import { UserEdit, UserList, UserShow } from './Users';
import { AuditList } from './Audit';

const i18nProvider = polyglotI18nProvider(
  () => ({
    ...russianMessages,
    ra: {
      ...russianMessages.ra,
      page: {
        ...russianMessages.ra.page,
        dashboard: 'Обзор',
        empty: 'Здесь пока нет записей',
        invite: 'Новые материалы появятся в этом разделе.',
      },
      action: {
        ...russianMessages.ra.action,
        show: 'Просмотр',
        clear_filters: 'Сбросить фильтры',
        select_columns: 'Столбцы',
        open_menu: 'Открыть меню',
        close_menu: 'Закрыть меню',
      },
      navigation: {
        ...russianMessages.ra.navigation,
        no_results: 'Ничего не найдено',
        page_out_from_end: 'Страница вне диапазона',
        partial_page_range_info: '%{offsetBegin}–%{offsetEnd} из %{total}',
      },
      notification: {
        ...russianMessages.ra.notification,
        updated: 'Изменения сохранены',
      },
    },
  }),
  'ru',
  [{ locale: 'ru', name: 'Русский' }],
);

const cmsTheme = (mode: 'light' | 'dark') => ({
  ...defaultTheme,
  palette: {
    mode,
    primary: { main: mode === 'dark' ? '#b39aff' : '#6d28d9' },
    secondary: { main: '#84cc16' },
    success: { main: mode === 'dark' ? '#81c784' : '#2e7d32' },
    error: { main: mode === 'dark' ? '#ff9da7' : '#d32f2f' },
    warning: { main: mode === 'dark' ? '#ffcc80' : '#ed6c02' },
    info: { main: mode === 'dark' ? '#90caf9' : '#0288d1' },
    background: {
      default: mode === 'dark' ? '#17151e' : '#f7f8fa',
      paper: mode === 'dark' ? '#23202b' : '#fff',
    },
    text: {
      primary: mode === 'dark' ? '#eeebf6' : '#252336',
      secondary: mode === 'dark' ? '#bbb4c9' : '#656173',
    },
    divider: mode === 'dark' ? '#36313f' : '#eeedf2',
  },
  shape: { borderRadius: 12 },
  typography: {
    fontFamily: 'Manrope, Arial, sans-serif',
    button: { textTransform: 'none' as const, fontWeight: 700 },
  },
  components: {
    ...defaultTheme.components,
    MuiButton: { styleOverrides: { root: { borderRadius: 9 } } },
    MuiCard: {
      styleOverrides: {
        root: {
          border: `1px solid ${mode === 'dark' ? '#36313f' : '#eeedf2'}`,
          boxShadow: 'none',
          backgroundImage: 'none',
        },
      },
    },
    MuiTableHead: {
      styleOverrides: { root: { backgroundColor: mode === 'dark' ? '#2b2635' : '#faf8fd' } },
    },
    MuiTableCell: {
      styleOverrides: { root: { borderColor: mode === 'dark' ? '#36313f' : '#eeedf2' } },
    },
  },
});

function CmsAppBar(props: AppBarProps) {
  return (
    <AppBar
      {...props}
      color="inherit"
      elevation={0}
      sx={{
        borderBottom: 1,
        borderColor: 'divider',
        bgcolor: 'background.paper',
        color: 'text.primary',
      }}
    >
      <Typography
        sx={{ fontWeight: 900, color: 'primary.main', mr: { xs: 1, sm: 3 }, fontSize: 23 }}
      >
        пульс.
      </Typography>
      <TitlePortal />
      <Box sx={{ flex: 1 }} />
      <Button
        component="a"
        href="/"
        startIcon={<ArrowLeft size={17} />}
        sx={{ whiteSpace: 'nowrap' }}
      >
        На сайт
      </Button>
    </AppBar>
  );
}

function CmsLayout(props: LayoutProps) {
  return (
    <Layout
      {...props}
      appBar={CmsAppBar}
      sx={{
        '& .RaLayout-content': {
          padding: { xs: 1, sm: 3 },
          maxWidth: 1800,
          width: '100%',
          margin: '0 auto',
        },
      }}
    />
  );
}

export default function CmsApp() {
  const { user, refresh, resolvedTheme } = useApp();
  const authProvider = useMemo(() => createCmsAuthProvider(refresh), [refresh]);
  const theme = useMemo(() => cmsTheme(resolvedTheme), [resolvedTheme]);
  return (
    <Admin
      basename="/cms"
      title="Пульс · Управление сайтом"
      dataProvider={cmsDataProvider}
      authProvider={authProvider}
      i18nProvider={i18nProvider}
      dashboard={CmsDashboard}
      layout={CmsLayout}
      theme={theme}
      darkTheme={null}
      loginPage={false}
      requireAuth
      disableTelemetry
    >
      <Resource
        name="news"
        options={{ label: 'Публикации' }}
        icon={Newspaper}
        list={NewsList}
        show={NewsShow}
        edit={NewsEdit}
        recordRepresentation="title"
      />
      <Resource
        name="communities"
        options={{ label: 'Паблики' }}
        icon={LibraryBig}
        list={CommunityList}
        show={CommunityShow}
        edit={CommunityEdit}
        recordRepresentation="name"
      />
      {user?.canModerate && (
        <Resource
          name="reports"
          options={{ label: 'Жалобы' }}
          icon={Flag}
          list={ReportList}
          show={ReportShow}
          edit={ReportEdit}
          recordRepresentation="title"
        />
      )}
      {user?.role === 'ADMIN' && (
        <Resource
          name="users"
          options={{ label: 'Пользователи' }}
          icon={UsersRound}
          list={UserList}
          show={UserShow}
          edit={UserEdit}
          recordRepresentation="name"
        />
      )}
      {user?.role === 'ADMIN' && (
        <Resource
          name="audit"
          options={{ label: 'История действий' }}
          icon={History}
          list={AuditList}
        />
      )}
    </Admin>
  );
}
import { useMemo } from 'react';
