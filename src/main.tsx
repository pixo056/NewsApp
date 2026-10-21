import { StrictMode } from 'react';
import { createRoot } from 'react-dom/client';
import { createBrowserRouter, RouterProvider } from 'react-router-dom';
import { AppProvider } from './app/AppContext';
import { ErrorBoundary } from './app/ErrorBoundary';
import { App } from './app/App';
import './styles.css';
import './functional.css';

if ('serviceWorker' in navigator) {
  window.addEventListener('load', () => {
    void navigator.serviceWorker.register('/sw.js').catch(() => {});
  });
}

const router = createBrowserRouter([
  {
    path: '*',
    element: (
      <AppProvider>
        <App />
      </AppProvider>
    ),
  },
]);

createRoot(document.getElementById('root')!).render(
  <StrictMode>
    <ErrorBoundary>
      <RouterProvider router={router} />
    </ErrorBoundary>
  </StrictMode>,
);
