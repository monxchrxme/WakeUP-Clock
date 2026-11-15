const STATION_ID = /^[a-f0-9]{16}$/;
const SECRET = /^[a-f0-9]{64}$/;
export const PERIOD = 30;
export const STORAGE_KEY = 'wake-up-station-v1';

export function validStation(station) {
  return !!station && STATION_ID.test(station.id) && SECRET.test(station.secret);
}

export function createStation() {
  return { id: randomHex(8), secret: randomHex(32) };
}

function randomHex(length) {
  return Array.from(crypto.getRandomValues(new Uint8Array(length)), n => n.toString(16).padStart(2, '0')).join('');
}

export function pairPayload(station) {
  if (!validStation(station)) throw new Error('Некорректная станция');
  return `wakeup://pair/v1/${station.id}/${station.secret}`;
}

export function timeSlot(now = Date.now()) {
  return Math.floor(now / 1000 / PERIOD) * PERIOD;
}

export async function signature(station, seconds) {
  if (!validStation(station) || !Number.isSafeInteger(seconds) || seconds < 0) throw new Error('Некорректная станция или время');
  const keyBytes = Uint8Array.from(station.secret.match(/../g), h => parseInt(h, 16));
  const key = await crypto.subtle.importKey('raw', keyBytes, { name: 'HMAC', hash: 'SHA-256' }, false, ['sign']);
  const message = new TextEncoder().encode(`wakeup:v1:${station.id}:${seconds}`);
  const bytes = new Uint8Array(await crypto.subtle.sign('HMAC', key, message));
  return Array.from(bytes, n => n.toString(16).padStart(2, '0')).join('');
}

export async function dismissPayload(station, now = Date.now()) {
  const seconds = timeSlot(now);
  return `wakeup://dismiss/v1/${station.id}/${seconds}/${await signature(station, seconds)}`;
}
