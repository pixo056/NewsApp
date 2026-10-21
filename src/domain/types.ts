export type Category =
  'Все' | 'Университет' | 'Технологии' | 'Наука' | 'Спорт' | 'Культура' | 'Общество';
export type PostStatus = 'pending' | 'published' | 'rejected';
export interface User {
  id: string;
  username: string;
  email: string;
  name: string;
  bio: string;
  role: 'USER' | 'ADMIN';
  verified: boolean;
  canModerate: boolean;
}
export interface Community {
  id: string;
  name: string;
  initials: string;
  color: string;
  category: Category;
  description: string;
  members: string;
  verified: boolean;
  ownerId: string;
  dailyLimit: number;
  canManage: boolean;
}
export interface MediaAsset {
  id: string;
  kind: 'image' | 'video';
  url: string;
  posterUrl?: string;
  width: number;
  height: number;
  duration?: number;
}
export interface Post {
  id: string;
  revisionId: string;
  title: string;
  text: string;
  category: Category;
  communityId?: string;
  author: string;
  authorId: string;
  publishedAt: string;
  image?: string;
  video: boolean;
  videoUrl?: string;
  poster?: string;
  media: MediaAsset[];
  views: string;
  reactions: number;
  liked: boolean;
  status: PostStatus;
  rejectionReason?: string;
  source: string;
  canEdit: boolean;
  canModerate: boolean;
  hasPendingChanges: boolean;
}
export interface Page<T> {
  items: T[];
  nextCursor?: string;
}
export interface Notification {
  id: string;
  message: string;
  newsId: string;
  read: boolean;
  createdAt: string;
}
export interface Report {
  id: string;
  newsId: string;
  title: string;
  reason: string;
  comment: string;
  status: string;
  resolution?: string;
  reporter: string;
}
