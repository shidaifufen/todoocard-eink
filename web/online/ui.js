import { findItems, kinds, loadArt, randomItems, songLyrics } from './sources.js';
import { analyzeCover, mountTone, neutral, panelColors, vivid } from './tone.js';
import { paint, paintPrompt } from './render.js';

export function mountOnline(root, canvas, { onCard = () => {}, onEmpty = () => {}, showPrompt = true, openLyrics,
  remember = false, initialTone = vivid, presets, onError = () => {} } = {}) {
  root.innerHTML = [
    '<div class="online-picker">',
    '<label>类型<select aria-label="类型" data-online="kind"></select></label>',
    '<form data-online="form"><label for="online-query">搜索</label><input id="online-query" aria-label="搜索关键词" data-online="query" type="search" maxlength="100">',
    '<div class="online-actions"><button data-online="submit" type="submit">搜索</button><button data-online="random" type="button">随机抽一张</button></div></form>',
    '<p data-online="status" role="status"></p>',
    '<label data-online="result-row" hidden>匹配结果<select aria-label="匹配结果" data-online="results"></select></label>',
    '<section data-online="music" hidden><label>版面<select aria-label="版面" data-online="style">',
    '<option value="cover">大封面</option><option value="single">大封面＋一句</option><option value="lyrics">封面＋歌词</option></select></label>',
    '<details data-online="appearance"><summary>文字与主题</summary><div class="theme-control"><label>主题色<select aria-label="主题色" data-online="theme"></select></label><span class="theme-swatch" data-online="theme-swatch" aria-hidden="true"></span></div>',
    '<div class="music-type"><label>文字颜色<select aria-label="文字颜色" data-online="ink"><option value="auto">自动搭配</option><option value="#fff">白字</option><option value="#000">黑字</option></select></label>',
    '<label>文字大小 <output data-online="font-value">自动</output><input aria-label="文字大小" data-online="font" type="range" min="85" max="140" step="5" value="140"></label></div></details>',
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
  let anchor = -1, end = -1, selectingEnd = false, tone = initialTone, redraw;
  const cacheKey = 'todocard-online-editor-v1';
  let cache = {};
  try { if (remember) cache = JSON.parse(localStorage.getItem(cacheKey)) || {}; } catch {}
  let cachedArt = '';
  const applyTone = mountTone($('tone'), tone, next => {
    tone = next;
    cancelAnimationFrame(redraw);
    redraw = requestAnimationFrame(draw);
  }, presets);

  function persist() {
    if (!remember) return;
    cache.kind = kind;
    cache.font = $('font').value;
    if (item && (!item.artwork || cachedArt)) cache[kind] = {
      item, artwork: cachedArt, verses, anchor, end, tone, style: $('style').value,
      theme: theme.value, ink: $('ink').value, query: $('query').value,
    };
    try { localStorage.setItem(cacheKey, JSON.stringify(cache)); } catch {}
  }

  function arrangeLyrics() {
    $('lyrics').replaceChildren();
    if (!openLyrics) verses.forEach((line, i) => {
      const button = document.createElement('button');
      button.type = 'button';
      button.textContent = line;
      button.onclick = () => selectLine(i);
      $('lyrics').append(button);
    });
    $('lyrics-row').hidden = !verses.length || $('style').value === 'cover';
  }

  function prepareArtwork(art) {
    cover = kind === 'music' ? analyzeCover(art) : null;
    if (cover) autoTheme.textContent = `自动取封面主色（${panelColors.find(([, color]) => color === cover.color)[0]}）`;
    $('tone').hidden = !art;
  }

  async function restore() {
    const saved = cache[kind];
    if (!saved?.item) return;
    const ticket = operation;
    try {
      const art = await loadArt(saved.artwork || null);
      if (!current() || ticket !== operation) return;
      item = saved.item;
      artwork = art;
      cachedArt = saved.artwork;
      verses = saved.verses || [];
      anchor = saved.anchor; end = saved.end;
      selectingEnd = false;
      $('query').value = saved.query || '';
      $('style').value = saved.style;
      theme.value = saved.theme;
      $('ink').value = saved.ink;
      tone = { ...neutral, ...saved.tone };
      applyTone(tone, false);
      prepareArtwork(art);
      arrangeLyrics();
      status.textContent = '已恢复上次卡片';
      draw();
    } catch {}
  }

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
        theme: color, ink: $('ink').value, fontScale: Number($('font').value) / 100,
        autoFont: $('font').value === '140', crop: cover?.crop,
      });
      if (kind === 'music' && style !== 'cover') showSelection(fits);
      ready = true;
      onCard(canvas, item, imageBounds);
      persist();
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
    if (verses.length) { arrangeLyrics(); draw(); return; }
    status.textContent = '正在查找歌词…';
    try {
      const lines = await songLyrics(track);
      if (!current() || token !== lyricOperation || item !== track) return;
      verses = lines;
      arrangeLyrics();
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
    cachedArt = '';
    if (remember && art) {
      const copy = document.createElement('canvas');
      const scale = Math.min(1, 680 / Math.max(art.naturalWidth, art.naturalHeight));
      copy.width = Math.max(1, Math.round(art.naturalWidth * scale));
      copy.height = Math.max(1, Math.round(art.naturalHeight * scale));
      copy.getContext('2d').drawImage(art, 0, 0, copy.width, copy.height);
      cachedArt = copy.toDataURL('image/jpeg', .88);
    }
    prepareArtwork(art);
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

  async function choose(fetchItems, random, excluded, selectFirst = false) {
    const ticket = ++operation;
    ++lyricOperation;
    status.textContent = random ? '正在抽取…' : '正在查询…';
    try {
      const matches = await fetchItems();
      if (!current() || ticket !== operation) return;
      if (!matches.length) throw new Error(kind === 'earthquake' ? '近一周没有符合条件的重要地震。' : '没有匹配的内容，试试其他关键词。');
      if (random) {
        const eligible = matches.filter(candidate => candidate.id !== item?.id && candidate.id !== excluded);
        const unseen = eligible.filter(candidate => !seen.has(candidate.id));
        const candidates = unseen.length ? unseen : eligible;
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
        $('result-row').hidden = selectFirst && matches.length === 1;
        status.textContent = '找到 ' + matches.length + ' 项';
        if (matches.length === 1 || selectFirst) {
          const first = matches.find(candidate => candidate.id !== excluded);
          if (!first) throw new Error('暂无更新内容');
          await select(first, ticket);
          if (current() && ticket === operation) $('results').value = String(matches.indexOf(first) + 1);
          return current() && ticket === operation;
        }
      }
    } catch (error) {
      if (current() && ticket === operation) {
        status.textContent = error.message || '在线来源暂时不可用';
        onError(status.textContent);
      }
    }
  }

  $('kind').onchange = (event) => {
    operation++;
    lyricOperation++;
    kind = $('kind').value;
    $('query').value = '';
    $('query').placeholder = kinds[kind].placeholder;
    $('submit').textContent = kinds[kind].action || '搜索';
    $('random').hidden = Boolean(kinds[kind].action);
    $('result-row').hidden = true;
    $('music').hidden = kind !== 'music';
    $('lyrics-row').hidden = true;
    status.textContent = '';
    item = null;
    artwork = null;
    cachedArt = '';
    verses = [];
    cover = null;
    autoTheme.textContent = '自动取封面主色';
    $('tone').hidden = true;
    draw();
    if (event && remember) { persist(); void restore(); }
  };
  $('form').onsubmit = event => {
    event.preventDefault();
    void choose(() => findItems(kind, $('query').value), false, undefined, Boolean(kinds[kind].action));
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
    $('font-value').value = $('font').value === '140' ? '自动' : $('font').value + '%';
    persist();
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
  if (remember && Object.hasOwn(kinds, cache.kind || '')) $('kind').value = cache.kind;
  if (remember && cache.font) {
    $('font').value = cache.font;
    $('font-value').value = $('font').value === '140' ? '自动' : $('font').value + '%';
  }
  $('kind').onchange();
  if (remember) void restore();
  return { get ready() { return ready; }, applyLyrics(token, start, finish) {
    if (token !== lyricOperation || !item || kind !== 'music' || $('style').value === 'cover') return;
    if (start < 0) anchor = end = -1;
    else if (start < verses.length && finish >= 0 && finish < verses.length) {
      anchor = start;
      end = $('style').value === 'single' ? start : finish;
    }
    selectingEnd = false;
    draw();
  }, random: (category, excluded) => {
    $('style').value = 'cover';
    theme.value = 'auto';
    $('ink').value = 'auto';
    if ($('kind').value !== category) {
      $('kind').value = category;
      $('kind').onchange();
    }
    const direct = Boolean(kinds[category]?.action);
    return choose(() => randomItems(category), !direct, excluded, direct);
  } };
}
