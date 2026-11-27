import { mountOnline } from './ui.js';
const canvas = document.querySelector('#card');
const root = document.querySelector('#picker');
const options = new URLSearchParams(location.search);
const scheduled = options.get('scheduled') === '1';
let imageBounds, category, drawError, itemId;
let presets;
try { presets = Object.entries(JSON.parse(options.get('presets') || '{}')); } catch {}
function sendCard() {
  const area = imageBounds ? [imageBounds.x, imageBounds.y, imageBounds.width, imageBounds.height].join(',') : '';
  window.TodooScene.card(category, area + '|' + canvas.toDataURL('image/png').split(',')[1], itemId);
}
const picker = mountOnline(root, canvas, {
  showPrompt: false,
  remember: !scheduled,
  presets: presets?.length ? presets : undefined,
  initialTone: presets?.find(([name]) => name === options.get('defaultPreset'))?.[1],
  openLyrics: selection => window.TodooScene.lyrics(JSON.stringify(selection)),
  onError: message => { drawError = message; },
  onCard: (_canvas, item, bounds) => {
    category = root.querySelector('[data-online="kind"]').value;
    imageBounds = bounds;
    itemId = item.id;
    if (!scheduled) sendCard();
  },
});
window.applyLyrics = (token, start, end) => picker.applyLyrics(token, start, end);
window.drawRandom = async (category, excluded) => {
  try {
    drawError = '';
    if (await picker.random(category, excluded)) sendCard();
    else window.TodooScene.error(drawError || '此类别暂时没有可用的在线卡片');
  } catch (error) {
    window.TodooScene.error(error.message || '在线卡片渲染失败');
  }
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
