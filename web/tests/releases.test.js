import test from 'node:test';
import assert from 'node:assert/strict';
import { latestApk, fetchLatestApk, connectDownloadLink, RELEASES_URL, RELEASES_API } from '../public/releases.js';

const asset = (version, extra = {}) => ({
  name: `wake-up-clock-${version}-debug.apk`, state: 'uploaded', size: 100,
  browser_download_url: `${RELEASES_URL}/download/v${version}/wake-up-clock-${version}-debug.apk`, ...extra,
});
const release = (version, day, extra = {}) => ({
  id: day, tag_name: `v${version}`, draft: false, prerelease: true,
  published_at: `2026-11-${String(day).padStart(2, '0')}T12:00:00Z`, assets: [asset(version)], ...extra,
});
const chosen = version => latestApk([release(version, 16)]);

test('newest published APK includes prereleases and does not depend on API ordering', () => {
  const result = latestApk([release('0.3.1', 14, { prerelease: false }), release('0.5.0', 17), release('0.4.0', 16)]);
  assert.deepEqual(result, { version: '0.5.0', url: asset('0.5.0').browser_download_url });
});

test('drafts, non-APK releases, test APKs and unfinished uploads cannot replace a usable download', () => {
  const result = latestApk([
    release('0.5.0', 20, { draft: true }), release('0.4.3', 19, { assets: [] }),
    release('0.4.2', 18, { assets: [asset('0.4.2', { state: 'starter' })] }),
    release('0.4.1', 17, { assets: [asset('0.4.1', { name: 'app-androidTest.apk' })] }), release('0.4.0', 16),
  ]);
  assert.equal(result.version, '0.4.0');
});

test('download URLs are restricted to APKs in this repository', () => {
  for (const url of [
    'javascript:alert(1)', 'http://github.com/monxchrxme/WakeUP-Clock/releases/download/v1/a.apk',
    'https://github.com.evil.example/monxchrxme/WakeUP-Clock/releases/download/v1/a.apk',
    'https://github.com/other/repo/releases/download/v1/a.apk',
    'https://github.com/monxchrxme/WakeUP-Clock/releases/download/v1/a.exe',
  ]) assert.equal(latestApk([release('1.0.0', 16, { assets: [asset('1.0.0', { browser_download_url: url })] })]), null);
});

test('malformed metadata and releases without a usable APK have no download', () => {
  for (const payload of [null, {}, [], [null, {}], [release('0.4.0', 16, { published_at: null })],
    [release('0.4.0', 16, { assets: [asset('0.4.0', { size: 0 })] })]]) assert.equal(latestApk(payload), null);
});

test('fetching uses the public API without credentials, referrer or a cached version', async () => {
  let request;
  const result = await fetchLatestApk(async (url, options) => {
    request = { url, options };
    return { ok: true, json: async () => [release('0.4.0', 16)] };
  });
  assert.equal(result.version, '0.4.0');
  assert.equal(request.url, RELEASES_API);
  assert.equal(request.options.credentials, 'omit');
  assert.equal(request.options.referrerPolicy, 'no-referrer');
  assert.equal(request.options.cache, 'no-store');
  assert.deepEqual(request.options.headers, { Accept: 'application/vnd.github+json' });
});

test('rate limits, missing releases and timeouts reject instead of guessing an old APK', async () => {
  await assert.rejects(fetchLatestApk(async () => ({ ok: false, status: 403 })), /unavailable/);
  await assert.rejects(fetchLatestApk(async () => ({ ok: true, json: async () => [] })), /No published APK/);
  await assert.rejects(fetchLatestApk((_, { signal }) => new Promise((_, reject) => {
    signal.addEventListener('abort', () => reject(new Error('aborted')));
  }), 1), /aborted/);
});

class Link extends EventTarget {
  attributes = new Map();
  setAttribute(key, value) { this.attributes.set(key, value); }
  removeAttribute(key) { this.attributes.delete(key); }
}
function click(link, options = {}) {
  const event = new Event('click', { cancelable: true });
  Object.assign(event, { button: 0, ctrlKey: false, metaKey: false, shiftKey: false, altKey: false, ...options });
  link.dispatchEvent(event);
  return event;
}
const settle = () => new Promise(resolve => setImmediate(resolve));

test('opening the download section resolves a version; clicking rechecks for a newer release', async () => {
  const link = new Link(), status = {}, navigations = [];
  let calls = 0;
  const downloads = connectDownloadLink(link, status, {
    getLatest: async () => chosen(++calls === 1 ? '0.4.0' : '0.5.0'), navigate: url => navigations.push(url),
  });
  assert.equal(link.href, RELEASES_URL);
  await downloads.refresh();
  assert.ok(link.textContent.includes('0.4.0'));
  assert.equal(click(link).defaultPrevented, true);
  await settle();
  assert.deepEqual(navigations, [asset('0.5.0').browser_download_url]);
  assert.ok(link.textContent.includes('0.5.0'));
});

test('an outage replaces a previously known APK with the releases page and can recover', async () => {
  const link = new Link(), status = {};
  let calls = 0;
  const downloads = connectDownloadLink(link, status, { getLatest: async () => {
    if (++calls === 2) throw new Error('offline');
    return chosen('0.4.0');
  } });
  await downloads.refresh();
  await downloads.refresh();
  assert.equal(link.href, RELEASES_URL);
  assert.ok(!link.textContent.includes('0.4.0'));
  assert.ok(status.textContent.includes('список релизов'));
  await downloads.refresh();
  assert.equal(link.href, asset('0.4.0').browser_download_url);
});

test('overlapping checks and double clicks share one request and one download', async () => {
  const link = new Link(), status = {}, navigations = [];
  let finish, calls = 0;
  const downloads = connectDownloadLink(link, status, { getLatest: () => {
    calls++; return new Promise(resolve => { finish = resolve; });
  }, navigate: url => navigations.push(url) });
  const first = downloads.refresh();
  assert.equal(first, downloads.refresh());
  click(link); click(link);
  await settle();
  finish(chosen('0.5.0'));
  await first; await settle();
  assert.equal(calls, 1);
  assert.deepEqual(navigations, [asset('0.5.0').browser_download_url]);
});

test('modified clicks retain native open-in-new-tab behaviour', () => {
  const link = new Link(), status = {};
  let calls = 0;
  connectDownloadLink(link, status, { getLatest: () => { calls++; return chosen('0.4.0'); } });
  assert.equal(click(link, { ctrlKey: true }).defaultPrevented, false);
  assert.equal(click(link, { button: 1 }).defaultPrevented, false);
  assert.equal(calls, 0);
});
