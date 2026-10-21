import { Component } from 'react';
import type { ErrorInfo, ReactNode } from 'react';
export class ErrorBoundary extends Component<{ children: ReactNode }, { failed: boolean }> {
  state = { failed: false };
  static getDerivedStateFromError() {
    return { failed: true };
  }
  componentDidCatch(error: Error, info: ErrorInfo) {
    console.error('Ошибка интерфейса:', error, info.componentStack);
  }
  render() {
    if (this.state.failed)
      return (
        <main
          style={{
            maxWidth: 500,
            margin: '15vh auto',
            padding: 25,
            fontFamily: 'system-ui',
            lineHeight: 1.8,
          }}
        >
          <h1>Не удалось открыть страницу</h1>
          <p>
            Попробуйте перезагрузить приложение. Если ошибка повторяется, проверьте локальный
            сервер.
          </p>
          <button onClick={() => window.location.reload()}>Перезагрузить</button>
        </main>
      );
    return this.props.children;
  }
}
