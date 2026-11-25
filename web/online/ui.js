import { findItems, kinds, loadArt, randomItems, songLyrics } from './sources.js';
import { paint, paintPrompt } from './render.js';

export function mountOnline(root, canvas, { onCard = () => {}, onEmpty = () => {} } = {}) {
  root.innerHTML = [
    '<div class="online-picker">',
    '<label>类型<select aria-label="类型" data-online="kind"></select></label>',
    '<form data-online="form"><label>搜索<input aria-label="搜索关键词" data-online="query" type="search" maxlength="100"></label>',
    '<div class="online-actions"><button type="submit">搜索</button><button data-online="random" type="button">随机抽一张</button></div></form>',
    '<p data-online="status" role="status"></p>',
    '<label data-online="result-row" hidden>匹配结果<select aria-label="匹配结果" data-online="results"></select></label>',
    '<section data-online="music" hidden><label>版面<select aria-label="版面" data-online="style">',
    '<option value="cover">大封面</option><option value="lyrics">封面＋歌词</option></select></label>',
    '<label data-online="lyrics-row" hidden>歌词片段<select aria-label="歌词片段" data-online="lyrics"></select></label></section></div>',
  ].join('');
  const $ = name => root.querySelector('[data-online="' + name + '"]');
  for (const [value, { name }] of Object.entries(kinds)) $('kind').add(new Option(name, value));
  const status = $('status');
  let kind = 'music', item, artwork, verses = [], result = [];
  let operation = 0, lyricOperation = 0;
  const seen = new Set();
  const current = () => root.isConnected;
  let ready = false;

  function draw() {
    if (!current()) return;
    if (item) {
      const start = Number($('lyrics').value);
      const selected = $('style').value === 'lyrics' && verses.length ? verses.slice(start, start + 3) : [];
      paint(canvas, kind, item, artwork, selected);
      ready = true;
      onCard(canvas, item);
    } else {
      paintPrompt(canvas);
      ready = false;
      onEmpty();
    }
  }

  async function refreshLyrics() {
    const token = ++lyricOperation;
    const track = item;
    if (!track || $('style').value !== 'lyrics') {
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
      lines.forEach((line, i) => $('lyrics').add(new Option(line, i)));
      $('lyrics-row').hidden = !lines.length;
      status.textContent = lines.length ? '选择第一句，卡片显示接下来的最多三句。' : '这首歌暂无匹配歌词，已显示大封面。';
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
    verses = [];
    $('lyrics').replaceChildren();
    $('lyrics-row').hidden = true;
    if (seen.size >= 150) seen.delete(seen.values().next().value);
    seen.add(item.id);
    status.textContent = '已生成卡面';
    draw();
    if (kind === 'music' && $('style').value === 'lyrics') void refreshLyrics();
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
            return;
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
  $('lyrics').onchange = draw;
  $('style').onchange = refreshLyrics;
  $('kind').onchange();
  return { get ready() { return ready; } };
}
