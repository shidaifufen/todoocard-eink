const palette = [[0,0,0],[255,255,255],[255,255,0],[255,0,0],[0,0,255],[0,255,0]];
export const neutral = { brightness: 100, contrast: 100, saturation: 100, shadows: 0, snap: 0 };
export const vivid = { ...neutral, brightness: 107, contrast: 120, saturation: 130, snap: 40 };
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

export function analyzeCover(artwork) {
  const canvas = document.createElement('canvas');
  const size = 128;
  canvas.width = canvas.height = size;
  const context = canvas.getContext('2d', { willReadFrequently: true });
  context.drawImage(artwork, 0, 0, size, size);
  const pixels = context.getImageData(0, 0, size, size).data;

  function matteDepth(pixelIndex) {
    let depth = 0;
    for (; depth < Math.floor(size * .16); depth++) {
      let dark = 0, light = 0, transparent = 0;
      for (let position = 0; position < size; position++) {
        const i = 4 * pixelIndex(depth, position);
        const r = pixels[i], g = pixels[i+1], b = pixels[i+2];
        if (pixels[i+3] < 32) transparent++;
        else if (Math.max(r,g,b) <= 22) dark++;
        else if (Math.min(r,g,b) >= 241) light++;
      }
      if (Math.max(dark, light, transparent) < size * .98) break;
    }
    return depth;
  }
  let left = matteDepth((depth, y) => y * size + depth);
  let right = matteDepth((depth, y) => y * size + size - 1 - depth);
  let top = matteDepth((depth, x) => depth * size + x);
  let bottom = matteDepth((depth, x) => (size - 1 - depth) * size + x);
  // A single dark edge is often part of the artwork. Trim opposed, similarly sized bands only.
  if (Math.min(left, right) < 2 || Math.abs(left - right) > 3) left = right = 0;
  if (Math.min(top, bottom) < 2 || Math.abs(top - bottom) > 3) top = bottom = 0;
  const counts = [0, 0, 0, 0, 0, 0];
  for (let y = top; y < size - bottom; y++) {
    for (let x = left; x < size - right; x++) {
      const i = 4 * (y * size + x);
      if (pixels[i+3] < 128) continue;
      const r = pixels[i], g = pixels[i+1], b = pixels[i+2];
      const high = Math.max(r,g,b), low = Math.min(r,g,b), chroma = high - low;
      if (high < 52 || chroma < 24) {
        counts[(r + g + b) / 3 >= 128 ? 1 : 0]++;
        continue;
      }
      const gain = 255 / chroma;
      const color = nearest((r - low) * gain, (g - low) * gain, (b - low) * gain);
      counts[palette.indexOf(color)]++;
    }
  }
  const colorful = counts.slice(2);
  const most = Math.max(...colorful);
  const area = (size - left - right) * (size - top - bottom);
  const winner = most >= area * .035 ? colorful.indexOf(most) + 2 : counts[1] > counts[0] ? 1 : 0;
  return { color: panelColors[winner][1], crop: [
    artwork.naturalWidth * left / size, artwork.naturalHeight * top / size,
    artwork.naturalWidth * (size - left - right) / size,
    artwork.naturalHeight * (size - top - bottom) / size,
  ] };
}

export function tuneImage(context, x, y, width, height, settings) {
  const { brightness, contrast, saturation, shadows = 0, snap } = settings;
  const image = context.getImageData(x,y,width,height);
  const pixels = image.data;
  const clamp = v => Math.max(0, Math.min(255,v));
  const gain = contrast / 100;
  const offset = 128 * (1-gain) + (brightness-100)*2.55;
  for (let i=0; i<pixels.length; i+=4) {
    const gray = .299*pixels[i] + .587*pixels[i+1] + .114*pixels[i+2];
    const adjust = channel => clamp((gray + (channel-gray) * saturation/100)*gain + offset);
    let r = adjust(pixels[i]), g = adjust(pixels[i+1]), b = adjust(pixels[i+2]);
    if (shadows) {
      const luminance = (.299*r + .587*g + .114*b) / 255;
      const lift = shadows * 2.55 * luminance * (1 - luminance) ** 3;
      r = clamp(r + lift); g = clamp(g + lift); b = clamp(b + lift);
    }
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
  let value = { ...neutral, ...initial };
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
    ['saturation','饱和度',0,180], ['shadows','暗部提亮',0,100], ['snap','杂色吸附',0,100],
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
