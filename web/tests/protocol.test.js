import test from 'node:test';
import assert from 'node:assert/strict';
import { readFile } from 'node:fs/promises';
import vm from 'node:vm';
import { createStation, validStation, pairPayload, dismissPayload, signature, timeSlot } from '../public/protocol.js';

const vector = JSON.parse(await readFile(new URL('../../shared/protocol-vector.json', import.meta.url)));

test('browser HMAC matches independent Python / Android vector', async () => {
  assert.equal(await signature(vector, vector.seconds), vector.signature);
  assert.equal(await dismissPayload(vector, vector.seconds * 1000 + 29_999), vector.dismiss);
  assert.equal(pairPayload(vector), vector.pair);
});

test('QR changes on the boundary and contains no shared secret', async () => {
  const before = await dismissPayload(vector, vector.seconds * 1000 + 29_999);
  const after = await dismissPayload(vector, (vector.seconds + 30) * 1000);
  assert.notEqual(before, after);
  assert.ok(!before.includes(vector.secret));
  assert.equal(timeSlot(vector.seconds * 1000 - 1), vector.seconds - 30);
});

test('fresh stations use independent cryptographic secrets', () => {
  const first = createStation(), second = createStation();
  assert.ok(validStation(first));
  assert.notEqual(first.id, second.id);
  assert.notEqual(first.secret, second.secret);
});

test('invalid stored keys cannot silently generate codes', async () => {
  for (const station of [null, {}, { id: 'x', secret: 'y' }, { id: vector.id, secret: vector.secret + '0' }]) {
    assert.equal(validStation(station), false);
    assert.throws(() => pairPayload(station));
    await assert.rejects(() => signature(station, vector.seconds));
  }
});

test('vendored generator produces valid-sized symbols for both payloads', async () => {
  const context = vm.createContext({});
  vm.runInContext(await readFile(new URL('../public/vendor/qrcodegen.js', import.meta.url), 'utf8'), context);
  for (const payload of [vector.pair, vector.dismiss]) {
    const qr = context.qrcodegen.QrCode.encodeText(payload, context.qrcodegen.QrCode.Ecc.MEDIUM);
    assert.ok(qr.size >= 21 && qr.size <= 177);
    // Three finder patterns must be present. Full decoding is checked separately against ZXing.
    assert.ok(qr.getModule(0, 0));
    assert.ok(qr.getModule(qr.size - 1, 0));
    assert.ok(qr.getModule(0, qr.size - 1));
  }
});
