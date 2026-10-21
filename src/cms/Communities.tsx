import { Alert, Button } from '@mui/material';
import { Plus } from 'lucide-react';
import {
  BooleanField,
  BooleanInput,
  Datagrid,
  Edit,
  EditButton,
  List,
  ListButton,
  NumberField,
  NumberInput,
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
  maxValue,
  minLength,
  minValue,
  required,
} from 'react-admin';
import { useApp } from '../app/context';
import { EmptySection, RecordTitle } from './shared';

export function CommunityList() {
  return (
    <List
      title="Паблики"
      exporter={false}
      perPage={25}
      sort={{ field: 'name', order: 'ASC' }}
      filters={[
        <SearchInput source="q" placeholder="Название паблика" alwaysOn key="q" />,
        <SelectInput
          source="status"
          label="Тип паблика"
          alwaysOn
          key="status"
          emptyText="Все паблики"
          choices={[
            { id: 'official', name: 'Официальные' },
            { id: 'ordinary', name: 'Обычные' },
          ]}
        />,
      ]}
      actions={
        <TopToolbar>
          <Button component="a" href="/communities/new" startIcon={<Plus size={18} />}>
            Создать паблик
          </Button>
        </TopToolbar>
      }
      empty={
        <EmptySection
          title="Пабликов пока нет"
          text="Создайте сообщество — здесь можно будет управлять его названием, описанием и публикациями."
        />
      }
    >
      <Datagrid rowClick="show" bulkActionButtons={false}>
        <TextField source="name" label="Паблик" />
        <TextField source="category" label="Категория" />
        <BooleanField source="official" label="Официальный" />
        <NumberField source="dailyLimit" label="Заявок в сутки" />
        <TextField source="members" label="Подписчиков" />
        <EditButton label="Настроить" />
      </Datagrid>
    </List>
  );
}

export function CommunityShow() {
  return (
    <Show
      title={<RecordTitle field="name" fallback="Паблик" />}
      actions={
        <TopToolbar>
          <ListButton label="К пабликам" />
          <EditButton label="Настроить паблик" />
        </TopToolbar>
      }
    >
      <SimpleShowLayout>
        <TextField source="name" label="Название" sx={{ fontSize: '1.5rem', fontWeight: 800 }} />
        <TextField source="description" label="Описание" sx={{ whiteSpace: 'pre-wrap' }} />
        <TextField source="category" label="Категория" />
        <BooleanField source="official" label="Официальный паблик" />
        <NumberField source="dailyLimit" label="Лимит заявок в сутки" />
        <TextField source="members" label="Подписчиков" />
      </SimpleShowLayout>
    </Show>
  );
}

export function CommunityEdit() {
  const { user } = useApp();
  const admin = user?.role === 'ADMIN';
  return (
    <Edit
      title={<RecordTitle field="name" fallback="Настройки паблика" />}
      mutationMode="pessimistic"
      redirect="show"
      actions={
        <TopToolbar>
          <ListButton label="К пабликам" />
          <ShowButton label="Просмотр" />
        </TopToolbar>
      }
    >
      <SimpleForm
        warnWhenUnsavedChanges
        toolbar={
          <Toolbar>
            <SaveButton label="Сохранить настройки" />
          </Toolbar>
        }
      >
        <TextInput
          source="name"
          label="Название паблика"
          fullWidth
          validate={[required(), minLength(3), maxLength(100)]}
        />
        <TextInput
          source="description"
          label="Описание"
          fullWidth
          multiline
          minRows={4}
          validate={maxLength(500)}
        />
        {admin ? (
          <>
            <NumberInput
              source="dailyLimit"
              label="Лимит заявок в сутки"
              validate={[required(), minValue(1), maxValue(1000)]}
              helperText="Включает новые публикации и правки. Повышенный лимит также действует для владельца этого паблика."
            />
            <BooleanInput source="official" label="Официальный паблик университета" />
            <Alert severity="info">
              У официального паблика новости проверяет администрация платформы. Владелец обычного
              паблика проверяет его новости самостоятельно.
            </Alert>
          </>
        ) : (
          <Alert severity="info">
            Вы меняете описание своего паблика. Официальный статус и лимит публикаций назначает
            администрация платформы.
          </Alert>
        )}
      </SimpleForm>
    </Edit>
  );
}
