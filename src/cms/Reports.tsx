import { Box, Button } from '@mui/material';
import { Link } from 'react-router-dom';
import {
  Datagrid,
  DateField,
  Edit,
  EditButton,
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
  useCreatePath,
  useRecordContext,
} from 'react-admin';
import { Chip } from '@mui/material';
import type { CmsReport } from './contracts';
import { dateOptions, EmptySection, RecordTitle, VisibilityAction } from './shared';

function ReportStatus() {
  const record = useRecordContext<CmsReport>();
  return (
    <Chip
      size="small"
      color={record?.status === 'pending' ? 'warning' : 'success'}
      label={record?.status === 'pending' ? 'Ожидает решения' : 'Рассмотрена'}
    />
  );
}

function ReportActions({ editing = false }: { editing?: boolean }) {
  const record = useRecordContext<CmsReport>();
  const createPath = useCreatePath();
  return (
    <TopToolbar>
      <ListButton label="К жалобам" />
      {editing ? (
        <ShowButton label="Просмотр" />
      ) : (
        record?.status === 'pending' && <EditButton label="Рассмотреть" />
      )}
      {record && (
        <>
          <Button
            component={Link}
            to={createPath({ resource: 'news', type: 'show', id: record.newsId })}
          >
            Открыть новость
          </Button>
          <VisibilityAction newsId={record.newsId} />
        </>
      )}
    </TopToolbar>
  );
}

export function ReportList() {
  return (
    <List
      title="Жалобы"
      exporter={false}
      perPage={25}
      sort={{ field: 'createdAt', order: 'DESC' }}
      filters={[
        <SearchInput source="q" placeholder="Новость или причина жалобы" alwaysOn key="q" />,
        <SelectInput
          source="status"
          label="Состояние"
          alwaysOn
          key="status"
          emptyText="Все жалобы"
          choices={[
            { id: 'pending', name: 'Ожидают решения' },
            { id: 'resolved', name: 'Рассмотрены' },
          ]}
        />,
      ]}
      empty={
        <EmptySection
          title="Жалоб пока нет"
          text="Здесь появятся обращения читателей на новости доступных вам пабликов."
        />
      }
    >
      <Datagrid rowClick="show" bulkActionButtons={false}>
        <TextField source="title" label="Новость" />
        <TextField source="reason" label="Причина" />
        <TextField source="reporter" label="Отправитель" sortable={false} />
        <ReportStatus />
        <DateField
          source="createdAt"
          label="Отправлена"
          showTime
          locales="ru"
          options={dateOptions}
        />
        <ShowButton label="Открыть" />
      </Datagrid>
    </List>
  );
}

function ReportDetails() {
  return (
    <SimpleShowLayout>
      <TextField source="title" label="Новость" sx={{ fontSize: '1.4rem', fontWeight: 800 }} />
      <ReportStatus />
      <TextField source="reporter" label="Отправитель" />
      <TextField source="reason" label="Причина жалобы" />
      <TextField
        source="comment"
        label="Подробности"
        sx={{ whiteSpace: 'pre-wrap' }}
        emptyText="Без комментария"
      />
      <TextField
        source="resolution"
        label="Решение модератора"
        sx={{ whiteSpace: 'pre-wrap' }}
        emptyText="Решение ещё не принято"
      />
      <DateField
        source="createdAt"
        label="Дата обращения"
        showTime
        locales="ru"
        options={dateOptions}
      />
    </SimpleShowLayout>
  );
}

export function ReportShow() {
  return (
    <Show title={<RecordTitle fallback="Жалоба" />} actions={<ReportActions />}>
      <ReportDetails />
    </Show>
  );
}

export function ReportEdit() {
  return (
    <Edit
      title="Рассмотреть жалобу"
      mutationMode="pessimistic"
      redirect="show"
      actions={<ReportActions editing />}
    >
      <Box>
        <ReportDetails />
        <SimpleForm
          toolbar={
            <Toolbar>
              <SaveButton label="Сохранить решение" />
            </Toolbar>
          }
        >
          <TextInput
            source="resolution"
            label="Результат рассмотрения"
            fullWidth
            multiline
            minRows={4}
            validate={[required(), maxLength(2000)]}
            helperText="Опишите принятое решение. Если материал нарушает правила, отдельно нажмите «Скрыть новость»."
          />
        </SimpleForm>
      </Box>
    </Edit>
  );
}
