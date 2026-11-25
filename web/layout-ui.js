export const presets = [
  ['晨间简报', 'daily-brief'], ['出门天气', 'weather-field'], ['天气色卡', 'weather-tiles'],
  ['专注清单', 'focus-list'], ['诗词日历', 'verse-almanac'],
  ['蓝色天气', 'weather-blue'], ['一句＋天气', 'quote-weather'], ['默认版式', 'default'],
];
const presetURL = 'https://github.com/shidaifufen/todoocard-eink/blob/main/layouts/';
const names = { summary: '每日总结', quote: '每日一句', todo: '今日待办', progress: '时间进度',
  calendar: '月历', weather: '天气看板', weather2: '色块天气', countdown: '倒计时', poem: '每日诗词', custom: '自定义卡片' };

export function mountLayouts(root, adapter) {
  root.innerHTML = [
    '<details><summary>看板版式包</summary>',
    '<p data-layout-active class="hint"></p>',
    '<label>GitHub 链接<input data-layout-link type="url" placeholder="公开 GitHub JSON 文件链接"></label>',
    '<label>示例版式<select data-layout-preset><option value="">选一个版式预览</option></select></label>',
    '<div class="row" style="flex-wrap:wrap;gap:6px">',
    '<button type="button" data-layout-fetch>获取并预览</button>',
    '<button type="button" data-layout-file-button>导入 JSON 文件</button>',
    '<input data-layout-file type="file" accept=".json,application/json" hidden>',
    '</div>',
    '<div class="row" style="flex-wrap:wrap;gap:6px;margin-top:6px">',
    '<button type="button" data-layout-export>导出已应用版式</button>',
    '<button type="button" data-layout-undo>撤销</button>',
    '<button type="button" data-layout-reset>恢复内置</button>',
    '</div><p data-layout-status class="hint" role="status"></p>',
    '<div data-layout-preview hidden><h4 data-layout-name></h4>',
    '<label>预览卡片<select data-layout-kind></select></label>',
    '<figure data-layout-image style="margin:10px auto;max-width:260px"></figure>',
    '<div data-layout-options></div>',
    '<div class="row"><button type="button" data-layout-apply>应用版式</button>',
    '<button type="button" data-layout-cancel>取消预览</button></div></div></details>',
  ].join('');
  const $ = name => root.querySelector('[data-layout-' + name + ']');
  const link = $('link'), status = $('status'), preview = $('preview'), kind = $('kind'), image = $('image');
  const addOption = (select, title, value) => select.add(new Option(title, value));
  let current = {}, pending, original, busy = false, renderPreview;
  let lastLink;
  try { lastLink = localStorage.getItem('todoocard-layout-link'); } catch {}
  link.value = lastLink || presetURL + 'daily-brief.json';
  for (const [title, file] of presets) addOption($('preset'), title, file);

  function buttons() {
    for (const element of root.querySelectorAll('button,input,select')) element.disabled = busy;
    $('undo').disabled = busy || !Object.hasOwn(current, 'previous');
    $('reset').disabled = busy || !current.active;
    $('export').disabled = busy || !current.active;
    $('apply').disabled = busy || !pending || !renderPreview;
  }

  function show(state) {
    current = state;
    $('active').textContent = state.active ?
      (state.active.name || '导入的版式') + ' · ' + Object.keys(state.active.templates).map(k => names[k]).join('、') : '正在使用内置版式';
    buttons();
  }

  async function run(message, operation) {
    if (busy) return;
    busy = true;
    buttons();
    status.textContent = message;
    try {
      await operation();
    } catch (error) {
      status.textContent = error.message || String(error);
    } finally {
      busy = false;
      buttons();
    }
  }

  function clearPreview() {
    pending = null;
    original = null;
    edits = {};
    renderPreview = null;
    preview.hidden = true;
    image.replaceChildren();
    $('options').replaceChildren();
  }

  async function stage(pack) {
    clearPreview();
    pending = pack;
    original = structuredClone(pack);
    $('name').textContent = pack.name || '导入的版式';
    kind.replaceChildren();
    for (const [key, template] of Object.entries(pack.templates)) addOption(kind, template.name || names[key], key);
    const previews = {};
    for (const key of Object.keys(pack.templates)) previews[key] = await adapter.preview(pack, key);
    renderPreview = previews;
    showPreview();
    showOptions();
    preview.hidden = false;
    status.textContent = '预览已生成，确认后应用';
  }

  function showPreview() {
    const element = renderPreview[kind.value]();
    element.style.cssText = 'display:block;width:100%;height:auto;margin:auto;background:white';
    image.replaceChildren(element);
  }

  function showOptions() {
    const key = kind.value;
    const base = original.templates[key];
    const box = $('options');
    box.replaceChildren();
    const titleIndex = base.nodes.findIndex(node => node.type === 'text' && node.bold && node.text && !node.text.includes('{{'));
    const nodeStyleKeys = ['fill', 'color', 'border'];
    const hasBlue = base.nodes.some(node => nodeStyleKeys.some(field => node[field] === 'blue'));
    const hasList = base.nodes.some(node => node.type === 'list');
    const groups = [
      ['小时趋势', node => node.type === 'weatherHours'],
      ['三天预报', node => node.type === 'forecastBars'],
      ['底部引文', node => node.type === 'text' && /\{\{quote(?:By)?\}\}/.test(node.text || '')],
      ['诗词赏析', node => node.type === 'text' && (node.showIf === 'poemNote' || (node.text || '').includes('{{poemNote}}'))],
    ].filter(([, match]) => base.nodes.some(match));
    if (titleIndex < 0 && !hasBlue && !hasList && !groups.length) return;

    const form = document.createElement('div');
    box.append(form);
    const add = (labelText, type) => {
      const label = document.createElement('label');
      label.style.display = 'block';
      label.textContent = labelText;
      const input = document.createElement(type === 'select' ? 'select' : 'input');
      if (type !== 'select') input.type = type;
      label.append(input); form.append(label);
      return input;
    };
    const config = edits[key] || {};
    const title = titleIndex < 0 ? null : add('卡面标题', 'text');
    if (title) { title.maxLength = 48; title.value = config.title ?? base.nodes[titleIndex].text; }
    const accent = hasBlue ? add('强调色', 'select') : null;
    if (accent) {
      for (const [label, value] of [['蓝色', 'blue'], ['红色', 'red'], ['黑色', 'black']]) addOption(accent, label, value);
      accent.value = config.accent || 'blue';
    }
    const numbering = hasList ? add('列表编号 ', 'checkbox') : null;
    if (numbering) numbering.checked = config.numbering ?? base.nodes.some(node => node.type === 'list' && node.numbered);
    const toggles = groups.map(([label]) => {
      const input = add(label + ' ', 'checkbox');
      input.checked = config.shown?.[label] ?? true;
      return [label, input];
    });
    const button = document.createElement('button');
    button.type = 'button'; button.textContent = '更新预览';
    form.append(button);
    button.onclick = () => run('正在生成预览…', async () => {
      const shown = Object.fromEntries(toggles.map(([label, input]) => [label, input.checked]));
      const nodes = base.nodes.flatMap((node, i) => {
        if (groups.some(([label, match]) => !shown[label] && match(node))) return [];
        const changed = { ...node };
        if (i === titleIndex && title?.value.trim()) changed.text = title.value.trim();
        if (accent) for (const field of nodeStyleKeys) if (node[field] === 'blue') changed[field] = accent.value;
        if (numbering && node.type === 'list') changed.numbered = numbering.checked;
        return [changed];
      });
      const candidate = { ...pending, templates: { ...pending.templates, [key]: { ...base, nodes } } };
      const next = await adapter.preview(await adapter.parse(JSON.stringify(candidate)), key);
      pending = candidate;
      edits[key] = { title: title?.value, accent: accent?.value, shown, numbering: numbering?.checked };
      renderPreview[key] = next;
      showPreview();
      status.textContent = '预览已更新，确认后应用';
    });
  }

  let edits = {};

  function fetchLink() {
    return run('正在获取并预览版式…', async () => {
      clearPreview();
      const requested = link.value.trim();
      await stage(await adapter.fetch(requested));
      try { localStorage.setItem('todoocard-layout-link', requested); } catch {}
    });
  }
  $('preset').onchange = () => {
    const file = $('preset').value;
    if (file) { link.value = presetURL + file + '.json'; fetchLink(); }
  };
  $('fetch').onclick = fetchLink;
  $('file-button').onclick = () => $('file').click();
  $('file').onchange = () => run('正在读取并预览版式…', async () => {
    const file = $('file').files[0];
    $('file').value = '';
    if (!file) return;
    clearPreview();
    if (file.size > 256 * 1024) throw new Error('版式包不能超过 256 KB');
    await stage(await adapter.parse(await file.text()));
  });
  kind.onchange = () => { showPreview(); showOptions(); };
  $('cancel').onclick = () => { clearPreview(); buttons(); status.textContent = ''; };
  for (const action of ['apply', 'undo', 'reset']) {
    $(action).onclick = () => run('正在保存版式…', async () => {
      const selected = action === 'apply' ? kind.value : undefined;
      const state = await adapter.update(action, pending);
      show(state);
      clearPreview();
      await adapter.changed(selected);
      status.textContent = action === 'reset' ? '已恢复内置版式' : action === 'undo' ? '已恢复上一版' : '已应用到预览、手动推送和定时任务';
    });
  }
  $('export').onclick = () => {
    const data = new Blob([JSON.stringify(current.active, null, 2)], { type: 'application/json' });
    const url = URL.createObjectURL(data);
    Object.assign(document.createElement('a'), { href: url, download: 'todocard-layouts.json' }).click();
    setTimeout(() => URL.revokeObjectURL(url), 1000);
  };
  return run('正在读取当前版式…', async () => { show(await adapter.read()); status.textContent = ''; });
}
