// Light/dark theme: persisted in localStorage, falls back to the OS
// preference. Keeps data-theme (app CSS) and data-ag-theme-mode (AG Grid
// Theming API) in sync on <html>.

const KEY = 'trade-dashboard-theme';

function apply(theme) {
  document.documentElement.dataset.theme = theme;
  document.documentElement.dataset.agThemeMode = theme;
  const btn = document.getElementById('theme-toggle');
  if (btn) btn.textContent = theme === 'dark' ? '☀️' : '🌙';
}

export function initTheme() {
  const saved = localStorage.getItem(KEY);
  const preferred = window.matchMedia('(prefers-color-scheme: dark)').matches ? 'dark' : 'light';
  apply(saved || preferred);

  document.getElementById('theme-toggle').addEventListener('click', () => {
    const next = document.documentElement.dataset.theme === 'dark' ? 'light' : 'dark';
    localStorage.setItem(KEY, next);
    apply(next);
  });
}
