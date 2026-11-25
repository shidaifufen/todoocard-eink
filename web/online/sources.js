const endpoints = {
  apple: 'https://itunes.apple.com',
  lyrics: 'https://lrclib.net',
  idioms: 'https://raw.githubusercontent.com/pwxcoo/chinese-xinhua/master/data/idiom.json',
  english: 'https://api.datamuse.com/words',
};

export const kinds = {
  music: { name: '音乐封面', placeholder: '歌名、歌手或专辑' },
  movie: { name: '电影海报', placeholder: '电影名称，例如 星际穿越' },
  english: { name: '英语短语', placeholder: '例如 take off 或 look forward to' },
  idiom: { name: '成语词条', placeholder: '例如 持之以恒，或搜索拼音' },
};

const requests = new Map();
const dictionaryCacheKey = Math.random().toString(36).slice(2);
function url(base, parameters) {
  const result = new URL(base);
  result.search = new URLSearchParams(parameters);
  return result.href;
}

async function fetchJSON(address, maxBytes) {
  const timer = new AbortController();
  const deadline = setTimeout(() => timer.abort(), 25000);
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
  } finally { clearTimeout(deadline); }
}

function json(address, maxBytes = 1024 * 1024) {
  const old = requests.get(address);
  if (old && Date.now() < old.until) return old.data;
  if (requests.size >= 64) requests.delete(requests.keys().next().value);
  const record = { until: Date.now() + 15 * 60000 };
  record.data = fetchJSON(address, maxBytes).catch(error => {
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

async function songs(query, market) {
  const region = market || (/\p{Script=Han}/u.test(query) ? 'hk' : 'us');
  const search = country => json(url(endpoints.apple + '/search', {
    term: query, country, media: 'music', entity: 'song', limit: '30',
  })).then(data => (data.results || []).map(song).filter(Boolean));
  const first = await search(region);
  return first.length || region === 'us' ? unique(first) : unique(await search('us'));
}

async function chart(kind, country) {
  const feed = await json(endpoints.apple + '/' + country + '/rss/top' + (kind === 'music' ? 'songs' : 'movies') + '/limit=50/json');
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

function wikipediaFilms(query, language) {
  const wiki = 'https://' + language + '.wikipedia.org';
  return json(url(wiki + '/w/api.php', {
    action: 'query', generator: 'search', gsrsearch: query + (language === 'zh' ? ' 電影' : ' film'),
    gsrlimit: '14', prop: 'pageimages|description', pilicense: 'any', pithumbsize: '650', format: 'json', origin: '*',
  })).then(data => Object.values(data.query?.pages || {}).sort((a, b) => a.index - b.index).flatMap(item => {
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
  const language = /\p{Script=Han}/u.test(query) ? 'zh' : 'en';
  const [wiki, top] = await Promise.allSettled([wikipediaFilms(query, language), chart('movie', 'us')]);
  const matching = (top.value || []).filter(film => normalized(film.title).includes(normalized(query)));
  if (wiki.status === 'rejected' && top.status === 'rejected') throw wiki.reason;
  return unique([...(wiki.value || []), ...matching]);
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
  const all = await json(endpoints.idioms, 16 * 1024 * 1024);
  const plain = value => normalized(value).normalize('NFD').replace(/[\p{M}\s]+/gu, '');
  const filter = plain(query);
  const selected = filter ? all.filter(item => item.word?.includes(filter) ||
    plain(item.pinyin).includes(filter)).slice(0, 40) :
    all.filter(item => item.word?.length === 4 && item.explanation?.trim());
  return selected.map(item => ({ id: 'idiom:' + item.word, title: item.word, by: item.pinyin,
    details: [item.explanation, item.example, item.derivation].map(clean), labels: ['释义', '例句', '出处'],
    source: 'chinese-xinhua' }));
}

export async function findItems(kind, query) {
  query = clean(query).slice(0, 100);
  if (!query) throw new Error('请输入搜索内容');
  switch (kind) {
    case 'music': return songs(query);
    case 'movie': return films(query);
    case 'english': {
      const exact = await phrases(query);
      return exact.length ? exact : phrases(query + '*');
    }
    case 'idiom': return idioms(query);
    default: throw new Error('未支持的场景');
  }
}

const randomIndex = length => Math.floor(Math.random() * length);
export async function randomItems(kind) {
  if (kind === 'music') {
    const country = ['hk', 'us'][randomIndex(2)];
    return chart(kind, country).catch(() => chart(kind, country === 'hk' ? 'us' : 'hk'));
  }
  if (kind === 'movie') return chart(kind, 'us');
  if (kind === 'english') return phrases(verbs[randomIndex(verbs.length)] + ' *');
  return idioms('');
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

export function loadArt(artwork) {
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
