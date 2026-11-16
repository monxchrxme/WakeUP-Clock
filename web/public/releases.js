export const RELEASES_URL = 'https://github.com/monxchrxme/WakeUP-Clock/releases';
export const RELEASES_API = 'https://api.github.com/repos/monxchrxme/WakeUP-Clock/releases?per_page=100';

function apkUrl(value) {
  try {
    const url = new URL(value);
    return url.protocol === 'https:' && url.host === 'github.com' && !url.username && !url.password &&
      url.pathname.startsWith('/monxchrxme/WakeUP-Clock/releases/download/') &&
      url.pathname.toLowerCase().endsWith('.apk') ? url.href : null;
  } catch { return null; }
}

/** Include prereleases; a release is downloadable only after its APK finishes uploading. */
export function latestApk(releases) {
  if (!Array.isArray(releases)) return null;
  const published = releases.filter(release => release?.draft === false &&
    typeof release.tag_name === 'string' && release.tag_name.trim() && Number.isFinite(Date.parse(release.published_at)))
    .sort((a, b) => Date.parse(b.published_at) - Date.parse(a.published_at) || b.id - a.id);
  for (const release of published) {
    const asset = Array.isArray(release.assets) && release.assets.find(item => item?.state === 'uploaded' &&
      item.size > 0 && typeof item.name === 'string' && item.name.toLowerCase().endsWith('.apk') &&
      !/androidtest/i.test(item.name) && apkUrl(item.browser_download_url));
    if (asset) return { version: release.tag_name.trim().replace(/^v(?=\d)/i, ''), url: apkUrl(asset.browser_download_url) };
  }
  return null;
}

export async function fetchLatestApk(fetcher = globalThis.fetch, timeoutMs = 5_000) {
  const controller = new AbortController();
  const timeout = setTimeout(() => controller.abort(), timeoutMs);
  try {
    const response = await fetcher(RELEASES_API, {
      headers: { Accept: 'application/vnd.github+json' },
      cache: 'no-store', credentials: 'omit', referrerPolicy: 'no-referrer', signal: controller.signal,
    });
    if (!response.ok) throw new Error('GitHub Releases unavailable');
    const release = latestApk(await response.json());
    if (!release) throw new Error('No published APK');
    return release;
  } finally { clearTimeout(timeout); }
}

export function connectDownloadLink(link, status, {
  getLatest = fetchLatestApk, navigate = url => window.location.assign(url),
} = {}) {
  let pending = null;
  let downloading = false;
  function reset() {
    link.href = RELEASES_URL;
    link.textContent = 'Скачать актуальный APK на GitHub · Android 8+';
  }
  reset();
  function refresh() {
    if (pending) return pending;
    reset();
    status.textContent = 'Проверяем последнюю версию…';
    link.setAttribute('aria-busy', 'true');
    pending = Promise.resolve().then(getLatest).then(release => {
      link.href = release.url;
      link.textContent = `Скачать WakeUP Clock ${release.version} · APK · Android 8+`;
      status.textContent = 'Версия проверена на GitHub.';
      return release.url;
    }).catch(() => {
      reset();
      status.textContent = 'Не удалось проверить версию. Ссылка откроет список релизов на GitHub.';
      return RELEASES_URL;
    }).finally(() => { pending = null; link.removeAttribute('aria-busy'); });
    return pending;
  }
  link.addEventListener('click', event => {
    if (event.defaultPrevented || event.button !== 0 || event.metaKey || event.ctrlKey || event.shiftKey || event.altKey) return;
    event.preventDefault();
    if (downloading) return;
    downloading = true;
    void refresh().then(navigate).finally(() => { downloading = false; });
  });
  return { refresh };
}
