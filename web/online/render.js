import { finishGraphics, tuneImage, vivid } from './tone.js';

const BLACK = '#000', WHITE = '#fff', BLUE = '#00f', YELLOW = '#ff0', RED = '#f00';
const W = 528, H = 792;
const family = '"PingFang SC","Microsoft YaHei","Noto Sans CJK SC",sans-serif';

function rect(g, x, y, w, h, color) {
  g.fillStyle = color;
  g.fillRect(x, y, w, h);
}

function line(g, value, x, y, width, size, color = BLACK, bold = false, minSize = size, outline) {
  const font = (bold ? '700 ' : '400 ');
  let text = String(value || '');
  g.font = font + size + 'px ' + family;
  while (size > minSize && g.measureText(text).width > width) {
    g.font = font + --size + 'px ' + family;
  }
  if (outline) {
    g.lineWidth = 3;
    g.lineJoin = 'round';
    g.strokeStyle = outline;
    g.strokeText(text, x, y);
  }
  while (text.length && g.measureText(text).width > width) {
    const short = text.endsWith('…') ? text.slice(0, -2) : text.slice(0, -1);
    text = short ? short + '…' : '';
  }
  g.fillStyle = color;
  g.fillText(text, x, y);
}

function wrap(g, text, width) {
  const rows = [];
  for (const paragraph of text.split('\n')) {
    const units = /\s/.test(paragraph) && !/\p{Script=Han}/u.test(paragraph)
      ? paragraph.split(/(\s+)/).filter(Boolean) : [...paragraph];
    let row = '';
    for (const unit of units) {
      if (row && g.measureText(row + unit).width > width) {
        rows.push(row.trimEnd());
        row = unit.trimStart();
      } else row += unit;
    }
    rows.push(row);
  }
  return rows;
}

function block(g, value, x, y, width, height, size, color = BLACK, bold = false, fit = true, outline) {
  const style = bold ? '700 ' : '400 ';
  let rows, step;
  for (;;) {
    g.font = style + size + 'px ' + family;
    rows = wrap(g, String(value || ''), width);
    step = Math.round(size * 1.38);
    if (!fit || (rows.length - 1) * step + size * 1.2 <= height || size <= 17) break;
    size -= 2;
  }
  const cap = Math.max(1, Math.floor((height - size * 1.2) / step) + 1);
  for (let i = 0; i < Math.min(rows.length, cap); i++) {
    const row = i === cap - 1 && rows.length > cap ? rows[i] + '…' : rows[i];
    line(g, row, x, y + i * step, width, size, color, bold, size, outline);
  }
  return rows.length <= cap;
}

function artwork(g, img, x, y, width, height, tone, crop = [0, 0, img.naturalWidth, img.naturalHeight]) {
  const ratio = Math.max(width / crop[2], height / crop[3]);
  const w = Math.round(crop[2] * ratio), h = Math.round(crop[3] * ratio);
  const left = x + Math.floor((width - w) / 2), top = y + Math.floor((height - h) / 2);
  g.save();
  g.beginPath(); g.rect(x, y, width, height); g.clip();
  g.drawImage(img, ...crop, left, top, w, h);
  g.restore();
  const bounds = { x: Math.max(x,left), y: Math.max(y,top), width: Math.min(width,w), height: Math.min(height,h) };
  tuneImage(g,bounds.x,bounds.y,bounds.width,bounds.height,tone);
  return bounds;
}

function music(g, item, img, verses, tone, style, { theme = BLACK, ink = 'auto', fontScale = 1, crop } = {}) {
  const defaultInk = [WHITE, YELLOW].includes(theme) ? BLACK : WHITE;
  const textColor = ink === 'auto' ? defaultInk : ink;
  const outline = textColor === theme ? (textColor === WHITE ? BLACK : WHITE) : undefined;
  const lyricBackground = ink === WHITE ? BLACK : WHITE;
  const size = base => Math.round(base * fontScale);
  const accent = theme === YELLOW || theme === WHITE ? BLUE : YELLOW;
  if (style !== 'lyrics' || !verses.length) {
    const bounds = artwork(g, img, 0, 0, W, W, tone, crop);
    rect(g, 0, W, W, H-W, theme);
    rect(g, 28, 551, 64, 8, accent);
    block(g, item.title, 28, 574, 472, 87, size(41), textColor, true, true, outline);
    line(g, item.by, 28, 664, 472, size(24), textColor, false, 18, outline);
    if (style === 'single' && verses[0]) line(g, verses[0], 28, 706, 472, size(26), textColor, true, 18, outline);
    line(g, item.source + (style === 'single' && verses[0] ? ' · 歌词 LRCLIB' : ''), 28, 754, 472, size(18), textColor, false, size(18), outline);
    return { bounds, fits: true };
  }
  rect(g, 0, 0, W, 276, theme);
  const bounds = artwork(g, img, 0, 0, 276, 276, tone, crop);
  rect(g, 298, 43, 60, 7, accent);
  block(g, item.title, 298, 78, 202, 113, size(38), textColor, true, true, outline);
  line(g, item.by, 298, 210, 202, size(22), textColor, false, 18, outline);
  rect(g, 0, 276, W, H - 276, lyricBackground);
  const lyricInk = lyricBackground === WHITE ? BLACK : WHITE;
  const fits = block(g, verses.join('\n'), 32, 310, 464, 411, size(34), lyricInk, true, false);
  rect(g, 32, 736, 464, 3, BLUE);
  line(g, item.source + ' · 歌词 LRCLIB', 32, 754, 464, size(18), lyricInk);
  return { bounds, fits };
}

function movie(g, item, img, tone) {
  artwork(g,img,0,0,W,H,tone);
  const mask = g.createLinearGradient(0,590,0,H);
  mask.addColorStop(0,'#0000'); mask.addColorStop(.65,'#000e'); mask.addColorStop(1,BLACK);
  rect(g,0,590,W,H-590,mask);
  rect(g,0,686,W,H-686,BLACK);
  line(g,item.title,26,700,476,38,WHITE,true);
  if (item.year) line(g,item.year,26,754,476,22,YELLOW,true);
  return {x:0,y:0,width:W,height:686};
}

function dictionary(g, kind, item) {
  const idiom = kind === 'idiom';
  rect(g, 0, 0, W, 248, idiom ? YELLOW : BLUE);
  const ink = idiom ? BLACK : WHITE;
  line(g, idiom ? '成语' : '英语短语', 30, 26, 468, 24, ink, true);
  block(g, item.title, 30, 76, 468, idiom ? 116 : 143, idiom ? 86 : 58, ink, true);
  if (idiom) line(g, item.by, 32, 197, 464, 26, ink);
  const details = (item.details || []).map((value, i) => ({ value, label: item.labels?.[i] })).filter(x => x.value);
  const h = Math.floor(464 / Math.max(1, details.length));
  details.forEach(({ value, label }, i) => {
    const y = 272 + i * h;
    rect(g, 30, y + 4, 48, 6, i === 0 ? BLUE : i === 1 ? RED : YELLOW);
    line(g, label || '释义 ' + (i + 1), 95, y, 402, 18, BLACK, true);
    block(g, value, 30, y + 35, 468, h - 47, idiom ? 27 : 25);
  });
  rect(g, 0, 744, W, 48, BLACK);
  line(g, item.source, 30, 756, 468, 18, WHITE);
}

export function paint(canvas, kind, item, artwork, verses = [], tone = vivid, style = 'cover', typography) {
  const g = canvas.getContext('2d');
  g.save();
  g.textBaseline = 'top';
  g.textAlign = 'left';
  rect(g, 0, 0, W, H, WHITE);
  let imageBounds, fits = true;
  if (kind === 'music') ({ bounds: imageBounds, fits } = music(g, item, artwork, verses, tone, style, typography));
  else if (kind === 'movie') imageBounds = movie(g, item, artwork, tone);
  else dictionary(g, kind, item);
  g.restore();
  finishGraphics(g,imageBounds);
  return fits;
}

export function paintPrompt(canvas) {
  const g = canvas.getContext('2d');
  rect(g, 0, 0, W, H, WHITE);
  rect(g, 0, 0, W, 136, BLUE);
  g.textBaseline = 'top';
  line(g, '在线场景', 30, 43, 468, 48, WHITE, true);
  block(g, '搜索或随机抽一张\n选择后即可预览', 30, 199, 468, 218, 36, BLACK, true);
}
