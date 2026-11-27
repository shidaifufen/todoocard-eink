import { readFile, writeFile } from 'node:fs/promises';
import { findItems } from '../web/online/sources.js';

const jobs = [
  { kind: 'rates', title: '汇率速览', color: 'blue', query: '' },
  { kind: 'holidays', title: '假日倒数', color: 'yellow', query: '香港' },
  { kind: 'earthquake', title: '一周地震', color: 'red', query: '' },
  { kind: 'television', title: '剧集档案', color: 'black', titles: [
    'Sherlock', 'The Office', 'Planet Earth', 'Friends', 'Doctor Who', 'The Crown',
  ] },
];

const rect = (x, y, width, height, fill) => ({ type: 'rect', x, y, width, height, fill });
const text = (x, y, width, height, value, textSize, color, options) => ({
  type: 'text', x, y, width, height, text: String(value || '').replaceAll('{{', '｛｛'),
  textSize, color, shrink: true, ...options,
});

function createPack(job, item) {
  const ink = job.color === 'yellow' ? 'black' : 'white';
  const nodes = [
    rect(0, 0, 528, 230, job.color),
    text(28, 22, 472, 42, job.title, 26, ink, { bold: true }),
    rect(28, 65, 48, 5, ink),
    text(28, 78, 472, 103, item.title, job.kind === 'television' ? 50 : 75, ink, { bold: true }),
    text(28, 186, 472, 39, item.by, 25, ink),
  ];
  const area = item.summary ? 255 : 464;
  const height = Math.floor(area / Math.max(1, item.rows.length));
  item.rows.forEach(([label, value], i) => {
    const y = 245 + i * height;
    nodes.push(text(28, y + 8, 118, height - 20, label, 23, 'black', { bold: true }));
    nodes.push(text(156, y + 8, 344, height - 20, value, item.summary ? 25 : 34, 'black', { bold: true }));
    nodes.push(rect(28, y + height - 5, 472, 2, 'black'));
  });
  if (item.summary) {
    nodes.push(text(28, 507, 472, 31, '剧情简介', 21, 'blue', { bold: true }));
    nodes.push(text(28, 540, 472, 185, item.summary, 24, 'black', { wrap: true }));
  }
  nodes.push(rect(0, 736, 528, 56, 'black'));
  nodes.push(text(28, 746, 472, 34, item.source, 20, 'white'));
  return { schemaVersion: 1, name: job.title, templates: { custom: { name: job.title, nodes } } };
}

async function publish(job) {
  // Anchor the daily television rotation to Hong Kong time.
  const day = Math.floor((Date.now() + 8 * 3600_000) / 86400_000);
  const query = job.titles?.[day % job.titles.length] ?? job.query;
  const items = await findItems(job.kind, query);
  const item = items[0] || (job.kind === 'earthquake' && {
    title: '暂无记录', by: 'USGS 近一周重要地震', rows: [['数据范围', '最近七天']],
    source: 'USGS · 近一周重要地震',
  });
  if (!item) throw new Error(job.title + '暂无数据');
  const path = new URL('../layouts/scenes/live-' + job.kind + '.json', import.meta.url);
  const next = JSON.stringify(createPack(job, item), null, 2) + '\n';
  const previous = await readFile(path, 'utf8').catch(error => {
    if (error.code !== 'ENOENT') throw error;
    return '';
  });
  if (previous !== next) await writeFile(path, next);
  console.log(job.title, item.title);
}

const results = await Promise.allSettled(jobs.map(publish));
for (const result of results) if (result.status === 'rejected') console.error(result.reason.message);
if (results.some(result => result.status === 'rejected')) process.exitCode = 1;
