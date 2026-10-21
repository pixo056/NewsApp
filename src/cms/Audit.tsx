import { Datagrid, DateField, FunctionField, List, SearchInput, TextField } from 'react-admin';
import { dateOptions, EmptySection } from './shared';

const labels: Record<string, string> = {
  approve: 'Новость одобрена',
  reject: 'Заявка отклонена',
  hide: 'Новость скрыта',
  delete: 'Новость удалена автором',
  'cms-hide': 'Новость скрыта',
  'cms-restore': 'Публикация восстановлена',
  'cms-edit': 'Новость отредактирована',
  'community-settings': 'Лимит или статус паблика изменён',
  'cms-community-edit': 'Настройки паблика изменены',
  'cms-report-resolve': 'Жалоба рассмотрена',
  'cms-user-edit': 'Профиль пользователя изменён',
  'cms-user-block': 'Пользователь заблокирован',
  'cms-user-unblock': 'Пользователь разблокирован',
  'user.updated': 'Профиль пользователя изменён',
  'user.blocked': 'Пользователь заблокирован',
  'user.unblocked': 'Пользователь разблокирован',
};

export function AuditList() {
  return (
    <List
      title="История действий"
      exporter={false}
      perPage={25}
      sort={{ field: 'createdAt', order: 'DESC' }}
      filters={[<SearchInput source="q" placeholder="Действие или участник" alwaysOn key="q" />]}
      empty={
        <EmptySection
          title="История пока пуста"
          text="Здесь будут записаны решения модераторов и изменения в кабинете управления."
        />
      }
    >
      <Datagrid bulkActionButtons={false}>
        <DateField source="createdAt" label="Когда" showTime locales="ru" options={dateOptions} />
        <TextField source="username" label="Кто" />
        <FunctionField
          source="action"
          label="Действие"
          render={(record: { action: string }) => labels[record.action] || record.action}
        />
        <TextField
          source="detail"
          label="Подробности"
          sortable={false}
          sx={{ overflowWrap: 'anywhere', maxWidth: 600, display: 'block' }}
        />
      </Datagrid>
    </List>
  );
}
