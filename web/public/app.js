import { createStation, validStation, STORAGE_KEY, pairPayload, dismissPayload, timeSlot, PERIOD } from './protocol.js';

const $ = id => document.getElementById(id);
let station;
let renderedSlot = -1;
let generating = false;

function error(message) {
  $('error').textContent = message;
  $('error').hidden = false;
}

function qrSvg(payload) {
  const library = globalThis.qrcodegen;
  if (!library) throw new Error('Не загрузился генератор QR. Обнови страницу.');
  const code = library.QrCode.encodeText(payload, library.QrCode.Ecc.MEDIUM);
  const border = 4;
  const size = code.size + border * 2;
  const svg = document.createElementNS('http://www.w3.org/2000/svg', 'svg');
  svg.setAttribute('viewBox', `0 0 ${size} ${size}`);
  svg.setAttribute('shape-rendering', 'crispEdges');
  svg.setAttribute('xmlns', 'http://www.w3.org/2000/svg');
  const background = document.createElementNS(svg.namespaceURI, 'rect');
  background.setAttribute('width', '100%'); background.setAttribute('height', '100%'); background.setAttribute('fill', '#ffffff');
  svg.append(background);
  const path = document.createElementNS(svg.namespaceURI, 'path');
  let d = '';
  for (let y = 0; y < code.size; y++) for (let x = 0; x < code.size; x++) {
    if (code.getModule(x, y)) d += `M${x + border},${y + border}h1v1h-1z`;
  }
  path.setAttribute('d', d); path.setAttribute('fill', '#162b22');
  svg.append(path);
  return svg;
}

async function tick() {
  if (!station || generating) return;
  const now = Date.now();
  const slot = timeSlot(now);
  const remaining = Math.max(1, PERIOD - Math.floor(now / 1000) % PERIOD);
  $('countdown').textContent = `${remaining} с`;
  $('progress-fill').style.transform = `scaleX(${remaining / PERIOD})`;
  if (slot === renderedSlot) return;
  generating = true;
  // Hide the old visual immediately; never leave a stale QR on an inactive tab after resume.
  $('qr').replaceChildren();
  $('qr-status').textContent = 'Готовим свежий код…';
  try {
    const payload = await dismissPayload(station, now);
    if (slot === timeSlot()) {
      $('qr').replaceChildren(qrSvg(payload));
      $('qr-status').textContent = 'Свежий код · можно сканировать';
      renderedSlot = slot;
    }
  } catch (e) { error(e.message); }
  finally { generating = false; }
}

function activate() {
  $('welcome').hidden = true;
  $('active').hidden = false;
  $('pair').disabled = false;
  $('station-id').textContent = `#${station.id.slice(-6)}`;
  void tick();
}

$('create').addEventListener('click', () => {
  try {
    const fresh = createStation();
    localStorage.setItem(STORAGE_KEY, JSON.stringify(fresh));
    if (localStorage.getItem(STORAGE_KEY) !== JSON.stringify(fresh)) throw new Error('Не удалось сохранить ключ браузера');
    station = fresh;
    activate();
    openPair();
  } catch { error('Браузер не сохраняет данные. Разреши локальное хранилище и открой сайт в обычном режиме, затем попробуй снова.'); }
});

function openPair() {
  try {
    $('pair-qr').replaceChildren(qrSvg(pairPayload(station)));
    $('pair-dialog').showModal();
  } catch (e) { error(e.message); }
}
$('pair').addEventListener('click', openPair);
$('help').addEventListener('click', () => $('help-dialog').showModal());
document.querySelectorAll('[data-close]').forEach(button => button.addEventListener('click', () => $(button.dataset.close).close()));
$('pair-dialog').addEventListener('close', () => $('pair-qr').replaceChildren());

try {
  if (!crypto.subtle || !globalThis.isSecureContext) throw new Error('Для QR нужен защищённый адрес HTTPS или localhost.');
  const saved = localStorage.getItem(STORAGE_KEY);
  if (saved) {
    station = JSON.parse(saved);
    if (!validStation(station)) throw new Error('Сохранённый ключ повреждён. Очисти данные этого сайта и заново свяжи браузер с телефоном до следующего будильника.');
    activate();
  }
} catch (e) {
  $('create').disabled = true;
  error(e.message || 'Не удалось прочитать хранилище браузера.');
}

document.addEventListener('visibilitychange', () => { if (!document.hidden) void tick(); });
window.addEventListener('pageshow', () => { void tick(); });
window.addEventListener('storage', event => {
  if (event.key === STORAGE_KEY && event.newValue !== JSON.stringify(station)) {
    station = null;
    $('active').hidden = true;
    $('pair').disabled = true;
    error('Станция изменена в другой вкладке. Обнови страницу перед сканированием.');
  }
});
setInterval(() => { void tick(); }, 250);
