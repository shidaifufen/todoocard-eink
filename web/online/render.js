import { finishGraphics, tuneImage, vivid } from './tone.js';

const BLACK = '#000', WHITE = '#fff', BLUE = '#00f', YELLOW = '#ff0', RED = '#f00';
const W = 528, H = 792;
const family = '"PingFang SC","Microsoft YaHei","Noto Sans CJK SC",sans-serif';

function rect(g, x, y, w, h, color) {
  g.fillStyle = color;
  g.fillRect(x, y, w, h);
}

function line(g, value, x, y, width, size, color = BLACK, bold = false, minSize = size) {
  const font = (bold ? '700 ' : '400 ');
  let text = String(value || '');
  g.font = font + size + 'px ' + family;
  while (size > minSize && g.measureText(text).width > width) {
    g.font = font + --size + 'px ' + family;
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

function block(g, value, x, y, width, height, size, color = BLACK, bold = false) {
  const style = bold ? '700 ' : '400 ';
  let rows, step;
  for (;;) {
    g.font = style + size + 'px ' + family;
    rows = wrap(g, String(value || ''), width);
    step = Math.round(size * 1.38);
    if ((rows.length - 1) * step + size * 1.2 <= height || size <= 17) break;
    size -= 2;
  }
  const cap = Math.max(1, Math.floor((height - size * 1.2) / step) + 1);
  for (let i = 0; i < Math.min(rows.length, cap); i++) {
    const row = i === cap - 1 && rows.length > cap ? rows[i] + '…' : rows[i];
    line(g, row, x, y + i * step, width, size, color, bold);
  }
}

function artwork(g, img, x, y, width, height, tone, fill = false) {
  const ratio = (fill ? Math.max : Math.min)(width / img.naturalWidth, height / img.naturalHeight);
  const w = Math.round(img.naturalWidth * ratio), h = Math.round(img.naturalHeight * ratio);
  const left = x + Math.floor((width - w) / 2), top = y + Math.floor((height - h) / 2);
  g.save();
  g.beginPath(); g.rect(x, y, width, height); g.clip();
  g.drawImage(img, left, top, w, h);
  g.restore();
  const bounds = { x: Math.max(x,left), y: Math.max(y,top), width: Math.min(width,w), height: Math.min(height,h) };
  tuneImage(g,bounds.x,bounds.y,bounds.width,bounds.height,tone);
  return bounds;
}

function music(g, item, img, verses, tone, style) {
  if (style !== 'lyrics' || !verses.length) {
    const bounds = artwork(g, img, 0, 0, W, W, tone);
    rect(g, 0, W, W, H-W, BLACK);
    rect(g, 28, 551, 64, 8, YELLOW);
    block(g, item.title, 28, 575, 472, 79, 41, WHITE, true);
    line(g, item.by, 28, 662, 472, 24, WHITE);
    if (style === 'single' && verses[0]) line(g, verses[0], 28, 707, 472, 26, WHITE, true, 18);
    line(g, item.source + (style === 'single' && verses[0] ? ' · 歌词 LRCLIB' : ''), 28, 758, 472, 17, WHITE);
    return bounds;
  }
  rect(g, 0, 0, W, 276, BLACK);
  const bounds = artwork(g, img, 22, 22, 232, 232, tone);
  rect(g, 282, 43, 60, 7, YELLOW);
  block(g, item.title, 282, 78, 220, 113, 38, WHITE, true);
  line(g, item.by, 282, 205, 220, 22, WHITE);
  block(g, verses.join('\n'), 32, 310, 464, 399, 40, BLACK, true);
  rect(g, 32, 736, 464, 3, BLUE);
  line(g, item.source + ' · 歌词 LRCLIB', 32, 754, 464, 16);
  return bounds;
}

function movie(g, item, img, tone) {
  artwork(g,img,0,0,W,H,tone,true);
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

export function paint(canvas, kind, item, artwork, verses = [], tone = vivid, style = "cover") {
  const g = canvas.getContext('2d');
  g.save();
  g.textBaseline = 'top';
  g.textAlign = 'left';
  rect(g, 0, 0, W, H, WHITE);
  let imageBounds;
  if (kind === 'music') imageBounds = music(g, item, artwork, verses, tone, style);
  else if (kind === 'movie') imageBounds = movie(g, item, artwork, tone);
  else dictionary(g, kind, item);
  g.restore();
  finishGraphics(g,imageBounds);
}

export function paintPrompt(canvas) {
  const g = canvas.getContext('2d');
  rect(g, 0, 0, W, H, WHITE);
  rect(g, 0, 0, W, 136, BLUE);
  g.textBaseline = 'top';
  line(g, '在线场景', 30, 43, 468, 48, WHITE, true);
  block(g, '搜索或随机抽一张\n选择后即可预览', 30, 199, 468, 218, 36, BLACK, true);
}
