/* ==========================================================================
   秒存官网 · 交互脚本

   部署说明：
   - 页面默认按「与云控后台同域」部署，接口走相对路径 /downloader-api；
     如果官网放在别的域名，把 API_BASE 改成完整地址即可，例如
     const API_BASE = 'https://music.aiyun-jing.com:8443/downloader-api';
   ========================================================================== */

const API_BASE = window.DOWNLOADER_API_BASE || '/downloader-api';

/** 支持平台：short = 图标里的字，name = 展示名，color = 图标底色 */
const PLATFORMS = [
  { short: '抖', name: '抖音', color: '#161823' },
  { short: '快', name: '快手', color: '#ff4906' },
  { short: '红', name: '小红书', color: '#ff2442' },
  { short: '视', name: '微信视频号', color: '#07c160' },
  { short: '文', name: '微信公众号', color: '#0a9f54' },
  { short: 'B', name: 'B 站', color: '#00a1d6' },
  { short: '博', name: '微博', color: '#e6162d' },
  { short: '微', name: '微视', color: '#ff5f00' },
  { short: '西', name: '西瓜视频', color: '#19b394' },
  { short: '看', name: '好看视频', color: '#2b6cff' },
  { short: 'A', name: 'AcFun', color: '#fd4c5d' },
  { short: '优', name: '优酷', color: '#16a5ff' },
  { short: '腾', name: '腾讯视频', color: '#ff6022' },
  { short: '奇', name: '爱奇艺', color: '#00be06' },
  { short: '芒', name: '芒果 TV', color: '#ff8700' },
  { short: 'YT', name: 'YouTube', color: '#ff0000' },
  { short: 'X', name: 'X（Twitter）', color: '#14171a' },
  { short: 'TT', name: 'TikTok', color: '#010101' },
  { short: 'IG', name: 'Instagram', color: '#c13584' },
  { short: 'R', name: 'Reddit', color: '#ff4500' },
  { short: 'V', name: 'Vimeo', color: '#1ab7ea' },
  { short: 'TW', name: 'Twitch', color: '#9146ff' },
  { short: '网', name: '网易云音乐', color: '#c20c0c' },
  { short: 'QQ', name: 'QQ 音乐', color: '#31c27c' },
  { short: '酷', name: '酷狗音乐', color: '#2ca2f9' },
  { short: 'SC', name: 'SoundCloud', color: '#ff5500' },
];

/* ---------------------------------------------------------------- 渲染 */

function platformChip(item) {
  const chip = document.createElement('span');
  chip.className = 'plat';
  const icon = document.createElement('i');
  icon.style.background = item.color;
  icon.textContent = item.short;
  chip.append(icon, document.createTextNode(item.name));
  return chip;
}

function renderPlatforms() {
  // 只渲染两行滚动的平台标签（页面上不再有静态图标墙）
  document.querySelectorAll('[data-marquee]').forEach((group) => {
    PLATFORMS.forEach((item) => group.appendChild(platformChip(item)));
  });
}

/**
 * 版本号从下载接口响应头动态读取（download-filename 形如「秒存-macOS-1.8.14.zip」）。
 * 后端已通过 access-control-expose-headers 暴露该头；只探测体积最小的 macOS 包，
 * 拿到响应头后立刻中止下载。失败时保留 HTML 里的静态占位，官网不因接口异常而报错。
 */
async function refreshVersionFromCloud() {
  const controller = new AbortController();
  try {
    const res = await fetch(`${API_BASE}/app/download?platform=macos`, { signal: controller.signal });
    const raw = res.headers.get('download-filename') || res.headers.get('content-disposition') || '';
    controller.abort();
    let name = raw;
    try { name = decodeURIComponent(raw); } catch { /* 已是明文则原样使用 */ }
    const match = name.match(/(\d+\.\d+\.\d+)/);
    if (match) {
      document.querySelectorAll('[data-version]').forEach((node) => {
        node.textContent = match[1];
      });
    }
  } catch {
    /* 静态占位兜底，不打扰访客 */
  }
}

function setupDownloads() {
  document.querySelectorAll('[data-download]').forEach((link) => {
    const platform = link.getAttribute('data-download');
    // 不带 versionCode：接口返回「当前发布版」，发版后官网自动跟随，无需改代码
    link.setAttribute(
      'href',
      `${API_BASE}/app/download?platform=${encodeURIComponent(platform)}`,
    );
  });
}

/**
 * 用后台的真实文件大小回填下载卡片。
 *
 * 注意：安装包下载接口（/app/download）对 300MB 的大文件不支持 HEAD——会等 40 秒再返回一小段错误 JSON，
 * 所以这里走和 App 相同的路子：先注册一个匿名设备拿 token，再查版本接口里的 fileSize（毫秒级返回）。
 * HTML 里写的数字只是兜底，实际以接口为准，避免再出现「写着 0.4MB、实际 330MB」这种不符。
 */
function formatSize(bytes) {
  // 与后台「客户端版本」页保持同一口径（1024 进制、精确到一位小数），两边显示的数字一致
  if (!bytes) return '';
  if (bytes < 1024) return `${bytes} B`;
  if (bytes < 1024 * 1024) return `${(bytes / 1024).toFixed(0)} KB`;
  return `${(bytes / 1024 / 1024).toFixed(1)} MB`;
}

async function siteDeviceToken() {
  try {
    const cached = sessionStorage.getItem('vg_site_token');
    if (cached) return cached;
  } catch (error) { /* 隐私模式下 sessionStorage 不可用，忽略 */ }
  const deviceId = 'web-' + (crypto.randomUUID ? crypto.randomUUID().slice(0, 12) : String(Date.now()));
  const response = await fetch(`${API_BASE}/app/device/register`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({
      platform: 'web',
      deviceId,
      deviceName: '官网访客',
      appVersion: 'web',
      osVersion: navigator.userAgentData?.platform || navigator.platform || 'web',
    }),
  });
  const data = await response.json();
  const token = data?.data?.token || '';
  if (token) {
    try { sessionStorage.setItem('vg_site_token', token); } catch (error) { /* 同上 */ }
  }
  return token;
}

async function hydrateDownloadSizes() {
  const nodes = [...document.querySelectorAll('[data-size-platform]')];
  if (!nodes.length) return;
  const token = await siteDeviceToken();
  if (!token) return;
  const versions = [];
  await Promise.all(nodes.map(async (node) => {
    const platform = node.getAttribute('data-size-platform');
    try {
      const response = await fetch(
        `${API_BASE}/app/version/latest?platform=${encodeURIComponent(platform)}`,
        { headers: { Authorization: `Bearer ${token}` } },
      );
      const data = await response.json();
      const size = Number(data?.data?.fileSize || 0);
      if (size > 0) node.textContent = formatSize(size);
      const version = data?.data?.versionName;
      if (version) {
        versions.push(version);
        // 下载卡片各显示自己平台的版本号（三端可能不一致，例如某次只更新了 Mac 版）
        document.querySelectorAll(`[data-version-platform="${platform}"]`)
          .forEach((item) => { item.textContent = version; });
      }
    } catch (error) {
      /* 取不到就保留 HTML 里的兜底文案 */
    }
  }));
  // 页面顶部/底部这类不区分平台的版本号：统一显示其中最高的版本，避免来回跳
  const latest = versions.sort(compareVersions).pop();
  if (latest) {
    document.querySelectorAll('[data-version]').forEach((item) => { item.textContent = latest; });
  }
}

/** 版本号比较：1.8.18 > 1.8.17（各段按数字比较） */
function compareVersions(a, b) {
  const pa = String(a).split('.').map((part) => Number(part) || 0);
  const pb = String(b).split('.').map((part) => Number(part) || 0);
  for (let i = 0; i < Math.max(pa.length, pb.length); i += 1) {
    const diff = (pa[i] || 0) - (pb[i] || 0);
    if (diff) return diff;
  }
  return 0;
}

/* ---------------------------------------------------------------- 滚动效果 */

function setupReveal() {
  const nodes = document.querySelectorAll('.reveal');
  if (!('IntersectionObserver' in window)) {
    nodes.forEach((node) => node.classList.add('visible'));
    return;
  }
  const observer = new IntersectionObserver(
    (entries) => {
      entries.forEach((entry) => {
        if (entry.isIntersecting) {
          entry.target.classList.add('visible');
          observer.unobserve(entry.target);
        }
      });
    },
    { rootMargin: '0px 0px -8% 0px', threshold: 0.08 },
  );
  nodes.forEach((node) => observer.observe(node));
}

function setupNav() {
  const nav = document.getElementById('nav');
  if (!nav) return;
  const onScroll = () => nav.classList.toggle('scrolled', window.scrollY > 12);
  window.addEventListener('scroll', onScroll, { passive: true });
  onScroll();
}

/* ---------------------------------------------------------------- 初始化 */

refreshVersionFromCloud();
renderPlatforms();
setupDownloads();
hydrateDownloadSizes();
setupReveal();
setupNav();
