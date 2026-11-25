import { findItems, kinds, loadArt, randomItems, songLyrics } from './sources.js';
import { analyzeCover, mountTone, panelColors, vivid } from './tone.js';
import { paint, paintPrompt } from './render.js';

export function mountOnline(root, canvas, { onCard = () => {}, onEmpty = () => {}, showPrompt = true, openLyrics } = {}) {
  root.innerHTML = [
    '<div class="online-picker">',
    '<label>类型<select aria-label="类型" data-online="kind"></select></label>',
    '<form data-online="form"><label for="online-query">搜索</label><input id="online-query" aria-label="搜索关键词" data-online="query" type="search" maxlength="100">',
    '<div class="online-actions"><button type="submit">搜索</button><button data-online="random" type="button">随机抽一张</button></div></form>',
    '<p data-online="status" role="status"></p>',
    '<label data-online="result-row" hidden>匹配结果<select aria-label="匹配结果" data-online="results"></select></label>',
    '<section data-online="music" hidden><label>版面<select aria-label="版面" data-online="style">',
    '<option value="cover">大封面</option><option value="single">大封面＋一句</option><option value="lyrics">封面＋歌词</option></select></label>',
    '<div class="theme-control"><label>主题色<select aria-label="主题色" data-online="theme"></select></label><span class="theme-swatch" data-online="theme-swatch" aria-hidden="true"></span></div>',
    '<div class="music-type"><label>文字颜色<select aria-label="文字颜色" data-online="ink"><option value="auto">自动搭配</option><option value="#fff">白字</option><option value="#000">黑字</option></select></label>',
    '<label>文字大小 <output data-online="font-value">100%</output><input aria-label="文字大小" data-online="font" type="range" min="85" max="135" step="5" value="100"></label></div>',
    '<div data-online="lyrics-row" hidden><div class="lyric-summary"><output data-online="selection" aria-live="polite"></output><button type="button" data-online="clear-lyrics">清除</button></div><button type="button" data-online="open-lyrics" hidden>选择歌词</button><div data-online="lyrics" class="lyric-lines" role="group" aria-label="歌词片段"></div></div></section>',
    '<details data-online="tone" hidden></details></div>',
  ].join('');
  const $ = name => root.querySelector('[data-online="' + name + '"]');
  for (const [value, { name }] of Object.entries(kinds)) $('kind').add(new Option(name, value));
  const theme = $('theme');
  const autoTheme = new Option('自动取封面主色', 'auto');
  theme.add(autoTheme);
  for (const [name, color] of panelColors) theme.add(new Option(name, color));
  const status = $('status');
  let kind = 'music', item, artwork, verses = [], result = [];
  let cover, typographyTimer;
  let operation = 0, lyricOperation = 0;
  const seen = new Set();
  const current = () => root.isConnected;
  let ready = false;
  let anchor = -1, end = -1, selectingEnd = false, tone = vivid, redraw;
  mountTone($('tone'), tone, next => {
    tone = next;
    cancelAnimationFrame(redraw);
    redraw = requestAnimationFrame(draw);
  });

  function selectedLyrics() {
    if ($('style').value === 'cover' || anchor < 0) return [];
    return verses.slice(Math.min(anchor, end), Math.max(anchor, end) + 1);
  }

  function showSelection(fits = true) {
    const first = Math.min(anchor, end), last = Math.max(anchor, end);
    const selected = selectedLyrics();
    const hint = $('style').value === 'single' ? '点选一句' : selectingEnd ? '点选结束行' : '点选起始行';
    const characters = [...selected.join('')].length;
    const count = `已选 ${selected.length} 行 · ${characters} 字`;
    $('selection').textContent = openLyrics ? (selected.length ? count : `共 ${verses.length} 行`) :
      (selected.length ? `${count} · ${hint}` : hint);
    if (!fits) $('selection').textContent += ' · 卡面仅显示前几行';
    $('clear-lyrics').disabled = !selected.length;
    [...$('lyrics').children].forEach((button,i) =>
      button.setAttribute('aria-pressed', String(i>=first && i<=last)));
  }

  function selectLine(index) {
    if ($('style').value === 'single' || !selectingEnd) {
      anchor = end = index;
      selectingEnd = $('style').value !== 'single';
    } else {
      end = index;
      selectingEnd = false;
    }
    draw();
  }

  function draw() {
    if (!current()) return;
    if (item) {
      const style = $('style').value;
      const color = theme.value === 'auto' ? cover?.color : theme.value;
      $('theme-swatch').style.backgroundColor = color;
      const { fits, imageBounds } = paint(canvas, kind, item, artwork, selectedLyrics(), tone, style, {
        theme: color, ink: $('ink').value, fontScale: Number($('font').value) / 100, crop: cover?.crop,
      });
      if (kind === 'music' && style !== 'cover') showSelection(fits);
      ready = true;
      onCard(canvas, item, imageBounds);
    } else {
      if (showPrompt) paintPrompt(canvas);
      ready = false;
      onEmpty();
    }
  }

  async function refreshLyrics() {
    const token = ++lyricOperation;
    const track = item;
    if (!track || $('style').value === 'cover') {
      $('lyrics-row').hidden = true;
      if (track) status.textContent = '';
      draw();
      return;
    }
    status.textContent = '正在查找歌词…';
    try {
      const lines = await songLyrics(track);
      if (!current() || token !== lyricOperation || item !== track) return;
      verses = lines;
      $('lyrics').replaceChildren();
      if (!openLyrics) lines.forEach((line, i) => {
        const button = document.createElement('button');
        button.type = 'button';
        button.textContent = line;
        button.onclick = () => selectLine(i);
        $('lyrics').append(button);
      });
      $('lyrics-row').hidden = !lines.length;
      status.textContent = lines.length ? ($('style').value === 'single' ? '卡片底部显示一行歌词' : '起止行之间的歌词会一起选中') : '这首歌暂无匹配歌词，已显示大封面。';
    } catch {
      if (!current() || token !== lyricOperation || item !== track) return;
      $('lyrics-row').hidden = true;
      status.textContent = '歌词暂时无法获取，仍可使用大封面。';
    }
    draw();
  }

  async function select(hit, ticket) {
    const art = await loadArt(hit.artwork);
    if (!current() || ticket !== operation) return;
    item = hit;
    artwork = art;
    cover = null;
    if (kind === 'music') {
      cover = analyzeCover(art);
      autoTheme.textContent = `自动取封面主色（${panelColors.find(([, color]) => color === cover.color)[0]}）`;
    }
    verses = [];
    anchor = end = -1;
    selectingEnd = false;
    $('tone').hidden = !artwork;
    $('lyrics').replaceChildren();
    $('lyrics-row').hidden = true;
    if (seen.size >= 150) seen.delete(seen.values().next().value);
    seen.add(item.id);
    status.textContent = '已生成卡面';
    draw();
    if (kind === 'music' && $('style').value !== 'cover') void refreshLyrics();
  }

  async function choose(fetchItems, random) {
    const ticket = ++operation;
    ++lyricOperation;
    status.textContent = random ? '正在抽取…' : '正在查询…';
    try {
      const matches = await fetchItems();
      if (!current() || ticket !== operation) return;
      if (!matches.length) throw new Error('没有匹配的内容，试试其他关键词。');
      if (random) {
        let candidates = matches.filter(candidate => candidate.id !== item?.id && !seen.has(candidate.id));
        if (!candidates.length) candidates = matches.filter(candidate => candidate.id !== item?.id);
        if (!candidates.length) throw new Error('暂时没有其他结果，请再抽一次或换个关键词。');
        for (let attempt = 0; attempt < 3 && candidates.length; attempt++) {
          const index = Math.floor(Math.random() * candidates.length);
          const hit = candidates[index];
          try {
            await select(hit, ticket);
            if (!current() || ticket !== operation) return;
            result = [];
            $('result-row').hidden = true;
            return true;
          } catch (error) {
            candidates.splice(index, 1);
            if (!candidates.length || attempt === 2) throw error;
          }
        }
      } else {
        result = matches;
        $('results').replaceChildren(new Option('选择一项预览', ''));
        matches.forEach((hit, i) => $('results').add(new Option(hit.by ? hit.title + ' · ' + hit.by : hit.title, i + 1)));
        $('result-row').hidden = false;
        status.textContent = '找到 ' + matches.length + ' 项';
        if (matches.length === 1) {
          await select(matches[0], ticket);
          if (current() && ticket === operation) $('results').value = '1';
        }
      }
    } catch (error) {
      if (current() && ticket === operation) status.textContent = error.message || '在线来源暂时不可用';
    }
  }

  $('kind').onchange = () => {
    operation++;
    lyricOperation++;
    kind = $('kind').value;
    $('query').value = '';
    $('query').placeholder = kinds[kind].placeholder;
    $('result-row').hidden = true;
    $('music').hidden = kind !== 'music';
    $('lyrics-row').hidden = true;
    status.textContent = '';
    item = null;
    cover = null;
    autoTheme.textContent = '自动取封面主色';
    $('tone').hidden = true;
    draw();
  };
  $('form').onsubmit = event => {
    event.preventDefault();
    void choose(() => findItems(kind, $('query').value), false);
  };
  $('random').onclick = () => void choose(() => randomItems(kind), true);
  $('results').onchange = async () => {
    const hit = result[Number($('results').value) - 1];
    if (!hit) return;
    const ticket = ++operation;
    ++lyricOperation;
    status.textContent = '正在生成卡面…';
    try { await select(hit, ticket); }
    catch (error) { if (current() && ticket === operation) status.textContent = error.message; }
  };
  $('style').onchange = () => {
    if ($('style').value === 'single') end = anchor;
    selectingEnd = false;
    void refreshLyrics();
  };
  theme.onchange = draw;
  $('ink').onchange = draw;
  $('font').oninput = () => {
    $('font-value').value = $('font').value + '%';
    clearTimeout(typographyTimer);
    typographyTimer = setTimeout(draw, 90);
  };
  $('font').onchange = () => { clearTimeout(typographyTimer); draw(); };
  $('clear-lyrics').onclick = () => {
    anchor = end = -1;
    selectingEnd = false;
    draw();
  };
  if (openLyrics) {
    $('open-lyrics').hidden = false;
    $('lyrics').hidden = true;
    $('open-lyrics').onclick = () => openLyrics({ token: lyricOperation, lines: verses,
      anchor, end, single: $('style').value === 'single' });
  }
  $('kind').onchange();
  return { get ready() { return ready; }, applyLyrics(token, start, finish) {
    if (token !== lyricOperation || !item || kind !== 'music' || $('style').value === 'cover') return;
    if (start < 0) anchor = end = -1;
    else if (start < verses.length && finish >= 0 && finish < verses.length) {
      anchor = start;
      end = $('style').value === 'single' ? start : finish;
    }
    selectingEnd = false;
    draw();
  }, random: category => {
    $('style').value = 'cover';
    theme.value = 'auto';
    $('ink').value = 'auto';
    $('font').value = '100';
    $('font-value').value = '100%';
    if ($('kind').value !== category) {
      $('kind').value = category;
      $('kind').onchange();
    }
    return choose(() => randomItems(category), true);
  } };
}
