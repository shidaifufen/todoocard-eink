import { mountOnline } from './ui.js';
const canvas = document.querySelector('#card');
const root = document.querySelector('#picker');
let automatic = false;
const picker = mountOnline(root, canvas, {
  showPrompt: false,
  openLyrics: selection => window.TodooScene.lyrics(JSON.stringify(selection)),
  onCard: () => {
    if (!automatic) window.TodooScene.card(canvas.toDataURL('image/png').split(',')[1]);
  },
});
window.applyLyrics = (token, start, end) => picker.applyLyrics(token, start, end);
window.drawRandom = async category => {
  automatic = true;
  const success = await picker.random(category);
  window.TodooScene.card(success ? canvas.toDataURL('image/png').split(',')[1] : '');
};
const cssColor = n => '#' + (n & 0xffffff).toString(16).padStart(6,'0');
window.setTheme = (dark, ink, panel, accent, enabled) => {
  document.documentElement.style.colorScheme = dark ? 'dark' : 'light';
  root.style.setProperty('--scene-ink',cssColor(ink));
  root.style.setProperty('--scene-panel',cssColor(panel));
  root.style.setProperty('--scene-accent',cssColor(accent));
  root.inert = !enabled;
};
new ResizeObserver(() => window.TodooScene.height(Math.ceil(root.getBoundingClientRect().height) + 8)).observe(root);
