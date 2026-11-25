const palette = [[0,0,0],[255,255,255],[255,255,0],[255,0,0],[0,0,255],[0,255,0]];
export const neutral = { brightness: 100, contrast: 100, saturation: 100, snap: 0 };
export const vivid = { brightness: 107, contrast: 120, saturation: 130, snap: 40 };
export const panelColors = [
  ['黑色', '#000'], ['白色', '#fff'], ['黄色', '#ff0'],
  ['红色', '#f00'], ['蓝色', '#00f'], ['绿色', '#0f0'],
];

function nearest(r, g, b, limit = Infinity) {
  let best;
  for (const ink of palette) {
    const distance = (r-ink[0])**2 + (g-ink[1])**2 + (b-ink[2])**2;
    if (distance < limit) { limit = distance; best = ink; }
  }
  return best;
}

export function coverColor(artwork) {
  // Sample the cover without padding or tuning, then average the largest RGB bin.
  const canvas = document.createElement('canvas');
  canvas.width = canvas.height = 64;
  const context = canvas.getContext('2d', { willReadFrequently: true });
  context.drawImage(artwork, 0, 0, 64, 64);
  const pixels = context.getImageData(0, 0, 64, 64).data;
  const bins = new Map();
  for (let i = 0; i < pixels.length; i += 4) {
    if (pixels[i + 3] < 128) continue;
    const [r, g, b] = [pixels[i], pixels[i + 1], pixels[i + 2]];
    const key = (r >> 5) * 64 + (g >> 5) * 8 + (b >> 5);
    const bin = bins.get(key) || [0, 0, 0, 0];
    bin[0]++;
    bin[1] += r;
    bin[2] += g;
    bin[3] += b;
    bins.set(key, bin);
  }
  let dominant = [0, 0, 0, 0];
  for (const bin of bins.values()) if (bin[0] > dominant[0]) dominant = bin;
  const [count, r, g, b] = dominant;
  if (!count) return panelColors[0][1];
  const color = nearest(r / count, g / count, b / count);
  return panelColors[palette.indexOf(color)][1];
}

export function tuneImage(context, x, y, width, height, settings) {
  const { brightness, contrast, saturation, snap } = settings;
  const image = context.getImageData(x,y,width,height);
  const pixels = image.data;
  const clamp = v => Math.max(0, Math.min(255,v));
  const gain = contrast / 100;
  const offset = 128 * (1-gain) + (brightness-100)*2.55;
  for (let i=0; i<pixels.length; i+=4) {
    const gray = .299*pixels[i] + .587*pixels[i+1] + .114*pixels[i+2];
    const adjust = channel => clamp((gray + (channel-gray) * saturation/100)*gain + offset);
    const r = adjust(pixels[i]), g = adjust(pixels[i+1]), b = adjust(pixels[i+2]);
    const chroma = Math.max(r,g,b)-Math.min(r,g,b);
    let ink;
    if (snap && chroma < snap * .45) {
      const luma = .299*r + .587*g + .114*b;
      if (luma <= snap * .8) ink = palette[0];
      if (luma >= 255 - snap * .8) ink = palette[1];
    }
    if (snap && !ink) ink = nearest(r,g,b,3 * (snap * 1.1)**2);
    pixels[i] = ink?.[0] ?? r; pixels[i+1] = ink?.[1] ?? g; pixels[i+2] = ink?.[2] ?? b;
  }
  context.putImageData(image,x,y);
}

export function finishGraphics(context, artwork) {
  const image = context.getImageData(0,0,context.canvas.width,context.canvas.height);
  const pixels = image.data;
  const width = image.width;
  for (let y=0; y<image.height; y++) {
    for (let x=0; x<width; x++) {
      if (artwork && x >= artwork.x && x < artwork.x+artwork.width &&
          y >= artwork.y && y < artwork.y+artwork.height) continue;
      const i = (y*width+x)*4;
      const r=pixels[i], g=pixels[i+1], b=pixels[i+2];
      const ink = Math.max(r,g,b)-Math.min(r,g,b) < 42
        ? palette[.299*r+.587*g+.114*b >= 128 ? 1 : 0] : nearest(r,g,b);
      pixels[i]=ink[0]; pixels[i+1]=ink[1]; pixels[i+2]=ink[2];
    }
  }
  context.putImageData(image,0,0);
}

export function mountTone(host, initial, onChange) {
  let value = { ...initial };
  host.innerHTML = '<summary>图片调色</summary><div class="tone-presets"></div><div class="tone-sliders"></div>';
  const presets = host.querySelector('.tone-presets');
  const controls = host.querySelector('.tone-sliders');
  const sliders = {};
  function update(next) {
    value = { ...next };
    for (const [key, {input, output}] of Object.entries(sliders)) {
      input.value = value[key];
      output.textContent = value[key] + (key === 'snap' ? '' : '%');
    }
    onChange(value);
  }
  for (const [label, preset] of [['原色', neutral], ['鲜明', vivid], ['净色', {...vivid, snap: 75}]]) {
    const button = document.createElement('button');
    button.type = 'button'; button.textContent = label;
    button.onclick = () => update(preset);
    presets.append(button);
  }
  for (const [key, label, min, max] of [
    ['brightness','亮度',65,145], ['contrast','对比度',65,160],
    ['saturation','饱和度',0,180], ['snap','杂色吸附',0,100],
  ]) {
    const row = document.createElement('label');
    row.textContent = label + ' ';
    const output = document.createElement('output');
    const input = document.createElement('input');
    input.type = 'range'; input.min=min; input.max=max;
    input.setAttribute('aria-label',label);
    input.value = value[key];
    output.textContent = value[key] + (key === 'snap' ? '' : '%');
    input.oninput = () => update({...value,[key]:Number(input.value)});
    row.append(output,input);
    controls.append(row);
    sliders[key] = {input, output};
  }
}
