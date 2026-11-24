import test from 'node:test';
import assert from 'node:assert/strict';
import vm from 'node:vm';
import { readFile } from 'node:fs/promises';

const html = await readFile(new URL('../web/card.html', import.meta.url), 'utf8');
const source = html.slice(html.indexOf('let selectingDevice =')).split('// ── Pair session ──')[0];
function page(response) {
  const nodes = new Map(), calls = [], messages = [];
  const $ = id => { if (!nodes.has(id)) nodes.set(id, {}); return nodes.get(id); };
  const ctx = vm.createContext({ $, toast: msg => messages.push(msg),
    postJSON: async (path, body) => { calls.push({path,body}); return response; },
    startPair: async () => { calls.push({path:'pair'}); },
    load: async () => { calls.push({path:'load'}); },
  });
  vm.runInContext(source, ctx);
  return { ctx, nodes, calls, messages, run: js => vm.runInContext(js, ctx) };
}

test('Windows structured scan and legacy text preserve the full MAC address', () => {
  const ui = page();
  assert.equal(ui.run(`scanCandidates({found:[{addr:'FF:FF:99:80:39:01',name:'FFFF99803901'}]})[0].id`), 'FF:FF:99:80:39:01');
  assert.equal(ui.run(`scanCandidates({devices:['FF:FF:99:80:39:01 name=FFFF99803901 id=FF:FF:99:80:39:01']})[0].id`), 'FF:FF:99:80:39:01');
  assert.equal(ui.run(`scanCandidates({devices:['id=12345678-1234-1234-1234-123456789abc']})[0].id`), '12345678-1234-1234-1234-123456789abc');
  assert.equal(ui.run(`scanCandidates({devices:['id=']}).length`), 0);
});

test('selecting the Windows card persists it then actually starts pairing', async () => {
  const ui = page({ok:true, card:{device_id:'FF:FF:99:80:39:01',device_name:'FFFF99803901'}});
  await ui.run(`useDevice('FF:FF:99:80:39:01','FFFF99803901')`);
  assert.equal(ui.calls[0].body.device_id, 'FF:FF:99:80:39:01');
  assert.deepEqual(ui.calls.map(c => c.path), ['/api/card/config','pair','load']);
  assert.equal(ui.nodes.get('devId').textContent, 'FF:FF:99:80:39:01');
});

test('empty address or failed persistence never starts pairing or claims success', async () => {
  for (const response of [{ok:false,error:'保存失败'}, {ok:true,card:{device_id:''}}]) {
    const ui = page(response);
    await ui.run(`useDevice('')`);
    assert.equal(ui.calls.length, 0);
    await ui.run(`useDevice('FF:FF:99:80:39:01')`);
    assert.equal(ui.calls.filter(c => c.path === 'pair').length, 0);
    assert.ok(ui.messages.at(-1).startsWith('选择失败：'));
  }
});
