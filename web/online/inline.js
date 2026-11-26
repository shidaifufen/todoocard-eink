import { mountOnline } from './ui.js';
const canvas = document.querySelector('#card');
const root = document.querySelector('#picker');
let automatic = false;
let imageBounds;
const options = new URLSearchParams(location.search);
let presets;
try { presets = Object.entries(JSON.parse(options.get('presets') || '{}')); } catch {}
function sendCard() {
  const area = imageBounds ? [imageBounds.x, imageBounds.y, imageBounds.width, imageBounds.height].join(',') : '';
  window.TodooScene.card(area + '|' + canvas.toDataURL('image/png').split(',')[1]);
}
const picker = mountOnline(root, canvas, {
  showPrompt: false,
  remember: options.get('scheduled') !== '1',
  presets: presets?.length ? presets : undefined,
  initialTone: presets?.find(([name]) => name === options.get('defaultPreset'))?.[1],
  openLyrics: selection => window.TodooScene.lyrics(JSON.stringify(selection)),
  onCard: (_canvas, _item, bounds) => {
    imageBounds = bounds;
    if (!automatic) sendCard();
  },
});
window.applyLyrics = (token, start, end) => picker.applyLyrics(token, start, end);
window.drawRandom = async category => {
  automatic = true;
  const success = await picker.random(category);
  if (success) sendCard();
  else window.TodooScene.card('');
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
