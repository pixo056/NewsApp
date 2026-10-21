import { Alert, Chip } from '@mui/material';
import {
  BooleanField,
  Datagrid,
  DateField,
  Edit,
  EditButton,
  FunctionField,
  List,
  ListButton,
  SaveButton,
  SearchInput,
  SelectInput,
  Show,
  ShowButton,
  SimpleForm,
  SimpleShowLayout,
  TextField,
  TextInput,
  Toolbar,
  TopToolbar,
  maxLength,
  required,
  useRecordContext,
} from 'react-admin';
import { useApp } from '../app/context';
import { json } from '../api/client';
import type { CmsUser } from './contracts';
import { ConfirmAction, dateOptions, EmptySection, RecordTitle } from './shared';

function UserState({ record }: { record: CmsUser }) {
  return (
    <Chip
      size="small"
      color={record.blocked ? 'error' : 'success'}
      label={record.blocked ? 'Заблокирован' : 'Активен'}
    />
  );
}

function UserBlockAction() {
  const record = useRecordContext<CmsUser>();
  const { user } = useApp();
  if (!record || record.role === 'ADMIN' || record.id === user?.id) return null;
  return (
    <ConfirmAction
      label={record.blocked ? 'Разблокировать' : 'Заблокировать'}
      title={
        record.blocked ? `Восстановить доступ для ${record.name}?` : `Заблокировать ${record.name}?`
      }
      color={record.blocked ? 'primary' : 'error'}
      description={
        record.blocked
          ? 'Пользователь снова сможет войти и пользоваться платформой. Его прежние публикации сохранятся.'
          : 'Текущие сессии пользователя завершатся. Он не сможет войти или отправлять материалы; его публикации сохранятся.'
      }
      reason={
        record.blocked
          ? undefined
          : {
              label: 'Причина блокировки',
              min: 5,
              max: 500,
              helper: 'От 5 до 500 символов. Причина сохранится в карточке пользователя.',
            }
      }
      request={(reason) =>
        json(
          `/cms/users/${record.id}`,
          { blocked: !record.blocked, blockReason: record.blocked ? '' : reason },
          'PATCH',
        )
      }
      success={record.blocked ? 'Доступ пользователя восстановлен' : 'Пользователь заблокирован'}
    />
  );
}

function UserActions({ editing = false }: { editing?: boolean }) {
  return (
    <TopToolbar>
      <ListButton label="К пользователям" />
      {editing ? <ShowButton label="Просмотр" /> : <EditButton label="Изменить профиль" />}
      <UserBlockAction />
    </TopToolbar>
  );
}

export function UserList() {
  return (
    <List
      title="Пользователи"
      exporter={false}
      perPage={25}
      sort={{ field: 'createdAt', order: 'DESC' }}
      filters={[
        <SearchInput source="q" placeholder="Имя, логин или почта" alwaysOn key="q" />,
        <SelectInput
          source="blocked"
          label="Доступ"
          alwaysOn
          key="blocked"
          emptyText="Все пользователи"
          choices={[
            { id: false, name: 'Активные' },
            { id: true, name: 'Заблокированные' },
          ]}
        />,
      ]}
      empty={
        <EmptySection
          title="Пользователи не найдены"
          text="Измените поисковый запрос или фильтр доступа."
        />
      }
    >
      <Datagrid rowClick="show" bulkActionButtons={false}>
        <TextField source="name" label="Имя" />
        <TextField source="username" label="Логин" />
        <TextField source="email" label="Почта" />
        <FunctionField
          source="role"
          label="Роль"
          render={(record: CmsUser) => (record.role === 'ADMIN' ? 'Администрация' : 'Пользователь')}
        />
        <FunctionField
          source="blocked"
          label="Доступ"
          render={(record: CmsUser) => <UserState record={record} />}
        />
        <DateField
          source="createdAt"
          label="Регистрация"
          showTime
          locales="ru"
          options={dateOptions}
        />
        <ShowButton label="Открыть" />
      </Datagrid>
    </List>
  );
}

export function UserShow() {
  return (
    <Show title={<RecordTitle field="name" fallback="Пользователь" />} actions={<UserActions />}>
      <SimpleShowLayout>
        <TextField source="name" label="Имя" sx={{ fontSize: '1.5rem', fontWeight: 800 }} />
        <TextField source="username" label="Логин" />
        <TextField source="email" label="Почта" />
        <BooleanField source="verified" label="Почта подтверждена" />
        <FunctionField
          source="role"
          label="Роль"
          render={(record: CmsUser) =>
            record.role === 'ADMIN' ? 'Администрация платформы' : 'Пользователь'
          }
        />
        <FunctionField
          source="blocked"
          label="Доступ"
          render={(record: CmsUser) => <UserState record={record} />}
        />
        <TextField source="blockReason" label="Причина блокировки" emptyText="—" />
        <TextField source="bio" label="О себе" emptyText="Не заполнено" />
        <DateField
          source="createdAt"
          label="Регистрация"
          showTime
          locales="ru"
          options={dateOptions}
        />
      </SimpleShowLayout>
    </Show>
  );
}

export function UserEdit() {
  return (
    <Edit
      title={<RecordTitle field="name" fallback="Профиль пользователя" />}
      mutationMode="pessimistic"
      redirect="show"
      actions={<UserActions editing />}
    >
      <SimpleForm
        warnWhenUnsavedChanges
        toolbar={
          <Toolbar>
            <SaveButton label="Сохранить профиль" />
          </Toolbar>
        }
      >
        <Alert severity="info" sx={{ mb: 3 }}>
          Здесь можно исправить имя и описание профиля. Роли, пароли и адреса почты через этот
          кабинет не меняются.
        </Alert>
        <TextInput source="name" label="Имя" fullWidth validate={[required(), maxLength(80)]} />
        <TextInput
          source="bio"
          label="Описание профиля"
          fullWidth
          multiline
          minRows={4}
          validate={maxLength(500)}
        />
      </SimpleForm>
    </Edit>
  );
}
