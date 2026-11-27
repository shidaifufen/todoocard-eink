const endpoints = {
  apple: 'https://itunes.apple.com',
  movies: 'https://v3-cinemeta.strem.io/catalog/movie/top/',
  posters: 'https://images.metahub.space/poster/medium/',
  posterRelay: 'https://wsrv.nl/',
  lyrics: 'https://lrclib.net',
  idioms: 'https://cdn.jsdelivr.net/gh/pwxcoo/chinese-xinhua@master/data/idiom.json',
  idiomsFallback: 'https://raw.githubusercontent.com/pwxcoo/chinese-xinhua/master/data/idiom.json',
  english: 'https://api.datamuse.com/words',
  rates: 'https://api.frankfurter.dev/v1/latest',
  holidays: 'https://date.nager.at/api/v3/PublicHolidays',
  earthquakes: 'https://earthquake.usgs.gov/earthquakes/feed/v1.0/summary/significant_week.geojson',
  television: 'https://api.tvmaze.com/search/shows',
};

export const kinds = {
  music: { name: '音乐封面', placeholder: '歌名、歌手或专辑' },
  movie: { name: '电影海报', placeholder: '简体、繁体或英文片名' },
  english: { name: '英语短语', placeholder: '例如 take off 或 look forward to' },
  idiom: { name: '成语词条', placeholder: '例如 持之以恒，或搜索拼音' },
  rates: { name: '汇率速览', placeholder: '例如 USD、人民币，或 USD HKD', action: '更新汇率' },
  holidays: { name: '假日倒数', placeholder: '例如 香港、美国、日本', action: '查看假日' },
  earthquake: { name: '一周地震', placeholder: '按英文地区筛选，例如 Japan', action: '查看最新' },
  television: { name: '剧集档案', placeholder: '例如 Sherlock 或 The Office' },
};

const requests = new Map();
const dictionaryCacheKey = Math.random().toString(36).slice(2);
function url(base, parameters) {
  const result = new URL(base);
  result.search = new URLSearchParams(parameters);
  return result.href;
}

async function fetchJSON(address, maxBytes, timeoutMs) {
  const timer = new AbortController();
  const deadline = setTimeout(() => timer.abort(), timeoutMs);
  try {
    const reply = await fetch(address, { signal: timer.signal, credentials: 'omit' });
    if (!reply.ok) throw new Error('接口返回 HTTP ' + reply.status);
    if (Number(reply.headers.get('content-length')) > maxBytes) throw new Error('返回数据过大');
    const reader = reply.body.getReader();
    const decoder = new TextDecoder();
    let source = '';
    let total = 0;
    try {
      for (;;) {
        const { done, value } = await reader.read();
        if (done) break;
        total += value.byteLength;
        if (total > maxBytes) throw new Error('返回数据过大');
        source += decoder.decode(value, { stream: true });
      }
    } finally { await reader.cancel().catch(() => {}); }
    return JSON.parse(source + decoder.decode());
  } catch (error) {
    if (timer.signal.aborted) throw new Error('在线接口请求超时');
    throw error;
  } finally { clearTimeout(deadline); }
}

function json(address, maxBytes = 1024 * 1024, timeoutMs = 25000) {
  const old = requests.get(address);
  if (old && Date.now() < old.until) return old.data;
  if (requests.size >= 64) requests.delete(requests.keys().next().value);
  const record = { until: Date.now() + 15 * 60000 };
  record.data = fetchJSON(address, maxBytes, timeoutMs).catch(error => {
    if (requests.get(address) === record) requests.delete(address);
    throw error;
  });
  requests.set(address, record);
  return record.data;
}

function imageURL(address, width, height) {
  if (!address) return '';
  const parsed = new URL(address);
  if (parsed.protocol !== 'https:' || !/^is\d+-ssl\.mzstatic\.com$/.test(parsed.hostname)) return '';
  return parsed.href.replace(/\/\d+x\d+bb\.(?:jpe?g|png|webp)(?=\?|$)/i, '/' + width + 'x' + height + 'bb.jpg');
}

const clean = value => String(value ?? '').replace(/\s+/g, ' ').trim();
const unique = items => Array.from(new Map(items.map(x => [x.id, x])).values());
const normalized = value => clean(value).normalize('NFKC').toLowerCase();

function song(item) {
  const artwork = imageURL(item.artworkUrl100, 600, 600);
  if (!artwork || !item.trackName || !item.artistName) return null;
  return { id: 'song:' + normalized(item.artistName + ' / ' + item.trackName), title: item.trackName,
    by: item.artistName, artwork, source: 'Apple Music', duration: item.trackTimeMillis / 1000 };
}

async function songs(query, market, timeoutMs) {
  const region = market || (/\p{Script=Han}/u.test(query) ? 'hk' : 'us');
  const search = country => json(url(endpoints.apple + '/search', {
    term: query, country, media: 'music', entity: 'song', limit: '30',
  }), undefined, timeoutMs).then(data => (data.results || []).map(song).filter(Boolean));
  const first = await search(region);
  return first.length || region === 'us' ? unique(first) : unique(await search('us'));
}

async function chart(kind, country, timeoutMs) {
  const address = endpoints.apple + '/' + country + '/rss/top' + (kind === 'music' ? 'songs' : 'movies') + '/limit=50/json';
  const feed = await json(address, undefined, timeoutMs);
  const entries = feed.feed?.entry || [];
  return entries.flatMap(item => {
    const artwork = imageURL(item['im:image']?.at(-1)?.label, 600, kind === 'movie' ? 900 : 600);
    const fullTitle = clean(item['im:name']?.label), by = clean(item['im:artist']?.label);
    const namedYear = kind === 'movie' ? fullTitle.match(/\s*\(((?:18|19|20)\d{2})\)$/) : null;
    const title = namedYear ? fullTitle.slice(0,namedYear.index) : fullTitle;
    const year = kind === 'movie' ? namedYear?.[1] || item['im:releaseDate']?.label?.slice(0,4) : undefined;
    if (!artwork || !title) return [];
    const id = kind === 'music' ? 'song:' + normalized(by + ' / ' + title) : 'movie:' + normalized(title);
    return [{ id, title, by, year, artwork, source: kind === 'music' ? 'Apple Music' : 'Apple TV' }];
  });
}

async function catalogFilms(query) {
  const term = encodeURIComponent(query.normalize('NFKC'));
  const data = await json(endpoints.movies + 'search=' + term + '.json', undefined, 8000);
  if (!Array.isArray(data.metas)) throw new Error('电影索引返回的数据无效');
  return unique(data.metas.flatMap(movie => {
    const id = movie.imdb_id || movie.id;
    const title = clean(movie.name);
    if (movie.type !== 'movie' || !/^tt\d{7,10}$/.test(id) || !title || !movie.poster) return [];
    const poster = endpoints.posters + id + '/img';
    const year = clean(movie.releaseInfo).match(/^(?:18|19|20)\d{2}(?=\b)/)?.[0];
    return [{
      id: 'movie:' + id, title, by: year, year,
      artwork: url(endpoints.posterRelay, { url: poster }), artworkFallback: poster,
      source: 'Cinemeta',
    }];
  })).slice(0, 25);
}

function wikipediaFilms(query, language) {
  const wiki = 'https://' + language + '.wikipedia.org';
  return json(url(wiki + '/w/api.php', {
    action: 'query', generator: 'search', gsrsearch: query + (language === 'zh' ? ' 電影' : ' film'),
    gsrlimit: '14', prop: 'pageimages|description', pilicense: 'any', pithumbsize: '650', format: 'json', origin: '*',
  }), undefined, 6500).then(data => Object.values(data.query?.pages || {}).sort((a, b) => a.index - b.index).flatMap(item => {
    if (!item.thumbnail?.source || !Number.isFinite(item.pageid)) return [];
    const artwork = new URL(item.thumbnail.source);
    if (artwork.protocol !== 'https:' || artwork.hostname !== 'upload.wikimedia.org') return [];
    const { width, height } = item.thumbnail;
    const description = clean(item.description);
    // Keep portrait movie artwork and exclude pages for people, books and fictional characters.
    if (!/film|movie|電影|电影|影片/i.test(description + ' ' + item.title) || !(height > width * 1.12)) return [];
    if (/actor|actress|producer|director|screenwriter|演员|演員|导演|導演/i.test(description) &&
        !/film (?:by|directed|starring)|movie (?:by|directed|starring)|[0-9]{4}.*(?:电影|電影)/i.test(description)) return [];
    const title = item.title.replace(/\s*[（(](?:\d{4}\s*)?(?:film|movie|電影|电影)[）)]\s*$/i, '');
    return [{ id: 'movie:' + normalized(title), title, by: description, year: (description + ' ' + item.title).match(/\b(?:18|19|20)\d{2}\b/)?.[0], artwork: artwork.href, source: 'Wikipedia' }];
  }));
}

async function films(query) {
  let unavailable = false;
  try {
    const matches = await catalogFilms(query);
    if (matches.length) return matches;
  } catch {
    unavailable = true;
  }
  const language = /\p{Script=Han}/u.test(query) ? 'zh' : 'en';
  const [wiki, top] = await Promise.allSettled([wikipediaFilms(query, language), chart('movie', 'us', 6500)]);
  const matching = (top.value || []).filter(film => normalized(film.title).includes(normalized(query)));
  const result = unique([...(wiki.value || []), ...matching]);
  if (!result.length && unavailable && wiki.status === 'rejected') {
    throw new Error('电影检索服务暂时不可用，请稍后重试');
  }
  return result;
}

const verbs = ['bring','call','carry','come','cut','drop','fall','figure','find','get','give','go','hold',
  'keep','leave','look','make','move','pick','point','pull','put','run','set','show','stand','take','turn','work'];

async function phrases(query) {
  // Datamuse reflects the request origin. Keep shared caches separated by origin.
  const words = await json(url(endpoints.english, {
    sp: query, md: 'd', max: '45', todocard_cache: dictionaryCacheKey,
  }));
  return words.flatMap(item => {
    if (!item.word?.includes(' ') || item.word.length > 42) return [];
    const meanings = (item.defs || []).map(def => clean(def.split('\t').at(-1))).filter(Boolean);
    if (!meanings.length) return [];
    return [{ id: 'phrase:' + normalized(item.word), title: item.word, details: meanings.slice(0, 3), source: 'Datamuse / Wiktionary' }];
  });
}

async function idioms(query) {
  const limit = 16 * 1024 * 1024;
  const all = await json(endpoints.idioms, limit).catch(() => json(endpoints.idiomsFallback, limit));
  const plain = value => normalized(value).normalize('NFD').replace(/[\p{M}\s]+/gu, '');
  const filter = plain(query);
  const selected = filter ? all.filter(item => item.word?.includes(filter) ||
    plain(item.pinyin).includes(filter)).slice(0, 40) :
    all.filter(item => item.word?.length === 4 && item.explanation?.trim());
  return selected.map(item => ({ id: 'idiom:' + item.word, title: item.word, by: item.pinyin,
    details: [item.explanation, item.example, item.derivation].map(clean), labels: ['释义', '例句', '出处'],
    source: 'chinese-xinhua' }));
}

const currencyNames = { 人民币: 'CNY', 美元: 'USD', 港币: 'HKD', 港元: 'HKD', 欧元: 'EUR', 日元: 'JPY', 英镑: 'GBP' };
const currencyCode = value => currencyNames[value] || value.toUpperCase();
const num = (value, digits = 0) => new Intl.NumberFormat('en', { maximumFractionDigits: digits }).format(value);
const regions = {
  HK: '中国香港', MO: '中国澳门', JP: '日本', KR: '韩国', US: '美国', GB: '英国', SG: '新加坡',
  AU: '澳大利亚', CA: '加拿大', DE: '德国', FR: '法国',
};

async function rates(query) {
  const [base = 'USD', ...requested] = query.split(/[\s,，/]+/).filter(Boolean).map(currencyCode);
  const symbols = [...new Set(requested.length ? requested : ['CNY', 'HKD', 'EUR', 'JPY', 'USD'])]
    .filter(code => code !== base).slice(0, 4);
  if (![base, ...symbols].every(code => /^[A-Z]{3}$/.test(code)) || !symbols.length) {
    throw new Error('请输入不同的货币代码，例如 USD HKD，或输入人民币');
  }
  const data = await json(url(endpoints.rates, { base, symbols: symbols.join(',') }));
  const rows = symbols.filter(code => Number.isFinite(data.rates?.[code]) && data.rates[code] > 0)
    .map(code => [code, num(data.rates[code], 6)]);
  if (!rows.length || !data.date) return [];
  return [{ id: 'rates:' + base + ':' + symbols.join(',') + ':' + data.date, title: '1 ' + base,
    by: data.date + ' · 参考汇率', rows, source: 'Frankfurter' }];
}

function localDay() {
  const now = new Date();
  return Date.UTC(now.getFullYear(), now.getMonth(), now.getDate());
}

async function holidays(query) {
  const region = Object.keys(regions).find(code =>
    regions[code] === query || regions[code].replace(/^中国/, '') === query) || query.toUpperCase() || 'HK';
  if (!/^[A-Z]{2}$/.test(region) || region === 'CN') {
    throw new Error('请输入支持的地区，例如香港、日本、美国，或两位地区代码');
  }
  const today = localDay();
  const year = new Date(today).getUTCFullYear();
  const upcoming = async year => {
    const data = await json(endpoints.holidays + '/' + year + '/' + region);
    return data.filter(day => Date.parse(day.date) >= today && day.global)
      .sort((a, b) => a.date.localeCompare(b.date))
      .filter((day, index, list) => index === 0 || day.date !== list[index - 1].date);
  };
  const dates = await upcoming(year);
  if (dates.length < 4) {
    try { dates.push(...await upcoming(year + 1)); }
    catch (error) { if (!dates.length) throw error; }
  }
  if (!dates.length) return [];
  const days = (Date.parse(dates[0].date) - today) / 86400000;
  return [{ id: 'holidays:' + region + ':' + today, title: days ? '还有 ' + days + ' 天' : '今天是假日',
    by: regions[region] || region, rows: dates.slice(0, 4).map(day => [day.date.slice(5), clean(day.localName || day.name)]),
    source: 'Nager.Date · 全国或全区公共假日' }];
}

async function earthquakes(query) {
  const data = await json(endpoints.earthquakes);
  return (data.features || []).map(({ id, properties: p, geometry }) => {
    if (!Number.isFinite(p?.mag) || !Number.isFinite(p.time) || !p.place ||
        !p.place.toLowerCase().includes(query.toLowerCase())) return null;
    const depth = geometry?.coordinates?.[2];
    return { id: 'quake:' + id + ':' + p.updated, title: 'M ' + num(p.mag, 1), by: p.place,
      rows: [['时间 UTC', new Date(p.time).toISOString().slice(0, 16).replace('T', ' ')],
        ['深度', Number.isFinite(depth) ? num(depth, 1) + ' km' : '暂无']],
      source: 'USGS · 近一周重要地震', time: p.time };
  }).filter(Boolean).sort((a, b) => b.time - a.time).slice(0, 18);
}

async function television(query) {
  const data = await json(url(endpoints.television, { q: query }));
  return data.map(({ show }) => {
    if (!show?.name || !show.id) return null;
    const summary = clean(show.summary?.replace(/<[^>]*>/g, ' ')
      .replace(/&(?:amp|quot|#39|nbsp);/g, entity => ({ '&amp;': '&', '&quot;': '"', '&#39;': "'", '&nbsp;': ' ' })[entity]));
    return { id: 'tv:' + show.id, title: clean(show.name), by: show.genres?.join(' · ') || show.language || '',
      rows: [['首播', show.premiered], ['状态', ({ Running: '播出中', Ended: '已完结', 'In Development': '制作中' })[show.status] || show.status],
        ['平台', show.network?.name || show.webChannel?.name], ['评分', show.rating?.average ? num(show.rating.average, 1) + ' / 10' : '暂无']]
        .filter(([, value]) => value), summary: summary.slice(0, 450), source: 'TVmaze · CC BY-SA' };
  }).filter(Boolean).slice(0, 15);
}

export async function findItems(kind, query) {
  query = clean(query).slice(0, 100);
  if (kind === 'rates') return rates(query);
  if (kind === 'holidays') return holidays(query);
  if (kind === 'earthquake') return earthquakes(query);
  if (!query) throw new Error('请输入搜索内容');
  switch (kind) {
    case 'music': return songs(query);
    case 'movie': return films(query);
    case 'english': {
      const exact = await phrases(query);
      return exact.length ? exact : phrases(query + '*');
    }
    case 'idiom': return idioms(query);
    case 'television': return television(query);
    default: throw new Error('未支持的场景');
  }
}

const randomIndex = length => Math.floor(Math.random() * length);
export async function randomItems(kind) {
  if (kind === 'music') {
    const timeoutMs = 8000;
    const country = ['hk', 'us'][randomIndex(2)];
    const loadChart = async market => {
      const items = await chart(kind, market, timeoutMs);
      if (!items.length) throw new Error('音乐榜单没有可用封面');
      return items;
    };
    const other = country === 'hk' ? 'us' : 'hk';
    return loadChart(country).catch(() => loadChart(other)).catch(() => {
      const performers = country === 'hk' ? ['周杰伦', '陈奕迅', '孙燕姿'] : ['Adele', 'Coldplay', 'Taylor Swift'];
      return songs(performers[randomIndex(performers.length)], country, timeoutMs);
    });
  }
  if (kind === 'movie') return chart(kind, 'us');
  if (kind === 'english') return phrases(verbs[randomIndex(verbs.length)] + ' *');
  if (kind === 'idiom') return idioms('');
  if (kind === 'television') {
    const titles = ['Sherlock', 'The Office', 'Planet Earth', 'Friends', 'Doctor Who', 'The Crown'];
    return television(titles[randomIndex(titles.length)]);
  }
  return findItems(kind, '');
}

export async function songLyrics(track) {
  const query = url(endpoints.lyrics + '/api/search', { track_name: track.title, artist_name: track.by });
  const results = await json(query, 2 * 1024 * 1024);
  const comparable = value => normalized(value).replace(/[\p{P}\s]/gu, '');
  const title = comparable(track.title);
  const artist = comparable(track.by);
  const matched = results.filter(item => comparable(item.trackName) === title && artist &&
    comparable(item.artistName).includes(artist) && !item.instrumental && item.plainLyrics &&
    (!Number.isFinite(track.duration) || Math.abs(item.duration - track.duration) <= 15));
  matched.sort((a, b) => Number.isFinite(track.duration) ?
    Math.abs(a.duration - track.duration) - Math.abs(b.duration - track.duration) : 0);
  const lyrics = matched[0]?.plainLyrics;
  return lyrics ? lyrics.split(/\r?\n/).map(line => line.trim()).filter(Boolean).slice(0, 180) : [];
}

export async function loadArt(artwork, fallback) {
  try {
    return await loadArtwork(artwork);
  } catch (error) {
    if (!fallback) throw error;
    return loadArtwork(fallback);
  }
}

function loadArtwork(artwork) {
  if (!artwork) return Promise.resolve(null);
  return new Promise((resolve, reject) => {
    const image = new Image();
    const timeout = setTimeout(() => { image.removeAttribute('src'); reject(new Error('图片下载超时')); }, 12000);
    image.crossOrigin = 'anonymous';
    image.onload = () => { clearTimeout(timeout); resolve(image); };
    image.onerror = () => { clearTimeout(timeout); reject(new Error('封面暂时无法载入')); };
    image.src = artwork;
  });
}
