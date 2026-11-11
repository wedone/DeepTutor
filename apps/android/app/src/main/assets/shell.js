// 老内核兼容垫片：Web 端打包产物用到了 Chrome 117+ 的新 API，
// WebView 内核偏旧时会在渲染阶段抛出 TypeError，导致页面白屏并反复重载。
// 这里在文档开始执行前补齐，避免整页不可用。
(function () {
  if (typeof Map.groupBy !== 'function') {
    Map.groupBy = function (items, callback) {
      var map = new Map();
      var index = 0;
      for (var item of items) {
        var key = callback(item, index++);
        var bucket = map.get(key);
        if (bucket) bucket.push(item);
        else map.set(key, [item]);
      }
      return map;
    };
  }
  if (typeof Object.groupBy !== 'function') {
    Object.groupBy = function (items, callback) {
      var result = Object.create(null);
      var index = 0;
      for (var item of items) {
        var key = callback(item, index++);
        if (Object.prototype.hasOwnProperty.call(result, key)) result[key].push(item);
        else result[key] = [item];
      }
      return result;
    };
  }
  if (typeof Object.hasOwn !== 'function') {
    Object.hasOwn = function (target, key) {
      return Object.prototype.hasOwnProperty.call(target, key);
    };
  }
  if (typeof Promise.withResolvers !== 'function') {
    Promise.withResolvers = function () {
      var resolve, reject;
      var promise = new Promise(function (res, rej) {
        resolve = res;
        reject = rej;
      });
      return { promise: promise, resolve: resolve, reject: reject };
    };
  }
  if (typeof Array.prototype.findLast !== 'function') {
    Array.prototype.findLast = function (fn, thisArg) {
      for (var i = this.length - 1; i >= 0; i--) {
        if (fn.call(thisArg, this[i], i, this)) return this[i];
      }
      return undefined;
    };
  }
  if (typeof Array.prototype.findLastIndex !== 'function') {
    Array.prototype.findLastIndex = function (fn, thisArg) {
      for (var i = this.length - 1; i >= 0; i--) {
        if (fn.call(thisArg, this[i], i, this)) return i;
      }
      return -1;
    };
  }
  if (typeof Array.prototype.toSorted !== 'function') {
    Array.prototype.toSorted = function (compareFn) {
      return Array.prototype.slice.call(this).sort(compareFn);
    };
  }
  if (typeof Array.prototype.toReversed !== 'function') {
    Array.prototype.toReversed = function () {
      return Array.prototype.slice.call(this).reverse();
    };
  }
  if (typeof Array.prototype.toSpliced !== 'function') {
    Array.prototype.toSpliced = function () {
      var copy = Array.prototype.slice.call(this);
      copy.splice.apply(copy, arguments);
      return copy;
    };
  }
  if (typeof Array.prototype.with !== 'function') {
    Array.prototype.with = function (index, value) {
      var copy = Array.prototype.slice.call(this);
      copy[index < 0 ? copy.length + index : index] = value;
      return copy;
    };
  }
})();

// DeepTutor Android 壳注入脚本：只做主题与滚动状态上报，不修改页面逻辑。
(function () {
  if (window.__deeptutorShellInstalled) return;
  window.__deeptutorShellInstalled = true;

  function reportTheme() {
    try {
      var dark = document.documentElement.classList.contains('dark');
      if (window.DeeptutorBridge) window.DeeptutorBridge.onThemeChanged(dark);
    } catch (e) {}
  }

  // 页面可能用内部容器滚动（document 的 scrollTop 恒为 0），
  // 因此从事件目标向上找最近的可滚动祖先，而不是只看 document。
  function nearestScrollable(node) {
    var el = node && node.nodeType === 1 ? node : null;
    while (el) {
      if (el.scrollHeight > el.clientHeight + 1) {
        var overflowY = getComputedStyle(el).overflowY;
        if (overflowY === 'auto' || overflowY === 'scroll' || overflowY === 'overlay') return el;
      }
      el = el.parentElement;
    }
    return null;
  }

  function reportScroll(node) {
    try {
      if (!window.DeeptutorBridge) return;
      var el = nearestScrollable(node) || document.scrollingElement || document.documentElement;
      if (!el) return;
      window.DeeptutorBridge.onScrollStateChanged(el.scrollTop <= 1);
    } catch (e) {}
  }

  function attach() {
    var root = document.documentElement;
    if (!root) {
      // document-start 阶段 documentElement 可能尚未创建，稍后重试
      setTimeout(attach, 16);
      return;
    }
    new MutationObserver(reportTheme).observe(root, {
      attributes: true,
      attributeFilter: ['class'],
    });
    document.addEventListener('scroll', function (event) {
      reportScroll(event.target === document ? document.documentElement : event.target);
    }, { passive: true, capture: true });
    document.addEventListener('touchstart', function (event) {
      reportScroll(event.target);
    }, { passive: true });
    reportTheme();
    reportScroll(root);
  }

  attach();
})();

// blob: 下载拦截：把页面生成的 blob 分块交给原生落盘，避免 WebView 无处可存。
(function () {
  if (window.__deeptutorBlobHook) return;
  window.__deeptutorBlobHook = true;

  var MAX_BYTES = 32 * 1024 * 1024;
  var CHUNK = 512 * 1024;
  var blobs = {};
  var seq = 0;
  var originalCreate = URL.createObjectURL.bind(URL);

  URL.createObjectURL = function (blob) {
    var url = originalCreate(blob);
    try { blobs[url] = blob; } catch (e) {}
    return url;
  };

  async function saveBlob(url, name) {
    try {
      var blob = blobs[url] || (await fetch(url)).blob();
      if (blob.size > MAX_BYTES) throw new Error('too large');
      var buffer = await blob.arrayBuffer();
      var bytes = new Uint8Array(buffer);
      var id = 'blob-' + (++seq);
      if (!window.DeeptutorBridge.beginBlob(id, name, blob.type || '')) return;
      var index = 0;
      for (var offset = 0; offset < bytes.length; offset += CHUNK) {
        var slice = bytes.subarray(offset, Math.min(offset + CHUNK, bytes.length));
        var binary = '';
        for (var i = 0; i < slice.length; i++) binary += String.fromCharCode(slice[i]);
        if (!window.DeeptutorBridge.appendBlob(id, index++, btoa(binary))) return;
      }
      window.DeeptutorBridge.finishBlob(id);
    } catch (e) {
      if (window.DeeptutorBridge) window.DeeptutorBridge.onDownloadFailed(String(e));
    }
  }

  document.addEventListener('click', function (event) {
    var anchor = event.target && event.target.closest ? event.target.closest('a[download]') : null;
    if (!anchor) return;
    var href = anchor.getAttribute('href') || '';
    if (href.startsWith('blob:')) {
      event.preventDefault();
      saveBlob(href, anchor.getAttribute('download') || 'download');
    }
  }, true);

  var originalOpen = window.open;
  window.open = function (url) {
    if (typeof url === 'string' && url.startsWith('blob:')) {
      saveBlob(url, 'download');
      return null;
    }
    return originalOpen.apply(window, arguments);
  };

  // 打开弹层（资源菜单、模型选择等）时收起软键盘，避免键盘遮挡浮层
  document.addEventListener('click', function (event) {
    var target = event.target;
    if (!target || !target.closest) return;
    if (!target.closest('button[aria-haspopup="dialog"]')) return;
    var active = document.activeElement;
    if (!active) return;
    var tag = active.tagName;
    if (tag === 'TEXTAREA' || tag === 'INPUT' || active.isContentEditable) {
      active.blur();
      if (window.DeeptutorBridge && window.DeeptutorBridge.hideKeyboard) {
        window.DeeptutorBridge.hideKeyboard();
      }
    }
  }, true);

  // 原生数学键盘回调：把 LaTeX 插入聊天输入框光标处
  window.__dtInsertMath = function (latex, mode) {
    var el = document.querySelector('textarea');
    if (!el) return false;
    var value = el.value || '';
    var start = typeof el.selectionStart === 'number' ? el.selectionStart : value.length;
    var end = typeof el.selectionEnd === 'number' ? el.selectionEnd : start;
    var snippet = mode === 'block'
      ? '\n$$\n' + latex + '\n$$\n'
      : ' $' + latex + '$ ';
    var setter = Object.getOwnPropertyDescriptor(HTMLTextAreaElement.prototype, 'value').set;
    setter.call(el, value.slice(0, start) + snippet + value.slice(end));
    el.dispatchEvent(new Event('input', { bubbles: true }));
    var caret = start + snippet.length;
    try {
      el.focus();
      el.setSelectionRange(caret, caret);
    } catch (e) {}
    return true;
  };
})();

// 工作区下拉菜单注入「APP设置」入口（位于 Web 版本号下方）。
// 菜单由页面按需渲染，路由切换会重建 DOM，因此用 MutationObserver 补挂。
(function () {
  var PANEL_SELECTOR = '.dt-workspace-panel';
  var ITEM_ID = 'dt-app-settings';
  var scheduled = false;

  function openSettings() {
    try {
      if (window.DeeptutorBridge && window.DeeptutorBridge.openSettings) {
        window.DeeptutorBridge.openSettings();
      }
    } catch (e) {}
    // 通知页面收起菜单（菜单为受控组件，不会感知到注入节点的点击）
    try {
      document.dispatchEvent(new KeyboardEvent('keydown', {
        key: 'Escape', code: 'Escape', keyCode: 27, bubbles: true,
      }));
    } catch (e2) {}
  }

  function mount() {
    var panel = document.querySelector(PANEL_SELECTOR);
    if (!panel || panel.querySelector('#' + ITEM_ID)) return;
    var footer = panel.querySelector('.dt-menu-footer');
    var btn = document.createElement('button');
    btn.id = ITEM_ID;
    btn.type = 'button';
    btn.textContent = 'APP设置';
    // 复用版本号行的字号、颜色与内边距，保证左对齐且视觉一致
    var fs = footer ? getComputedStyle(footer) : null;
    btn.style.cssText = 'display:flex;align-items:center;width:100%;margin:0;' +
      'background:none;border:none;cursor:pointer;text-align:left;' +
      (fs
        ? 'font-size:' + fs.fontSize + ';line-height:' + fs.lineHeight +
          ';color:' + fs.color + ';padding:' + fs.padding + ';'
        : 'font-size:11.5px;line-height:17.25px;color:var(--muted-foreground);padding:2px 4px 0 8px;');
    btn.addEventListener('click', openSettings);
    if (footer && footer.parentElement === panel) {
      footer.insertAdjacentElement('afterend', btn);
    } else {
      panel.appendChild(btn);
    }
  }

  function scheduleMount() {
    if (scheduled) return;
    scheduled = true;
    requestAnimationFrame(function () {
      scheduled = false;
      mount();
    });
  }

  function start() {
    mount();
    try {
      new MutationObserver(scheduleMount).observe(document.body, {
        childList: true,
        subtree: true,
      });
    } catch (e) {}
  }

  if (document.readyState === 'loading') {
    document.addEventListener('DOMContentLoaded', start, { once: true });
  } else {
    start();
  }
})();

// 首页「继续上次对话」：Web 端 /chat 每次都是新会话，这里在空会话页插一张卡片，
// 回到最近访问过的会话。记录只在本机 localStorage，不上报服务端。
(function () {
  if (window.__dtContinueInstalled) return;
  window.__dtContinueInstalled = true;

  var KEY = 'dt_last_chat_session';
  var CARD_ID = 'dt-continue-card';
  var STYLE_ID = 'dt-continue-style';
  var MAX_AGE = 7 * 24 * 60 * 60 * 1000;
  var lastPath = null;
  var timer = null;

  function readLast() {
    try {
      var raw = localStorage.getItem(KEY);
      if (!raw) return null;
      var data = JSON.parse(raw);
      if (!data || typeof data.id !== 'string' || !data.ts) return null;
      if (Date.now() - data.ts > MAX_AGE) return null;
      return data;
    } catch (e) {
      return null;
    }
  }

  /** 只有进入过真实会话（URL 带会话 id）才记录；空会话页停留不会产生记录。 */
  function remember() {
    var match = /^\/chat\/([A-Za-z0-9_-]+)\/?$/.exec(location.pathname);
    if (!match) return;
    try {
      localStorage.setItem(KEY, JSON.stringify({ id: match[1], ts: Date.now() }));
    } catch (e) {}
  }

  function relativeTime(ts) {
    var minutes = Math.floor((Date.now() - ts) / 60000);
    if (minutes < 1) return '刚刚';
    if (minutes < 60) return minutes + ' 分钟前';
    var hours = Math.floor(minutes / 60);
    if (hours < 24) return hours + ' 小时前';
    var days = Math.floor(hours / 24);
    return days === 1 ? '昨天' : days + ' 天前';
  }

  function ensureStyle() {
    if (document.getElementById(STYLE_ID)) return;
    var style = document.createElement('style');
    style.id = STYLE_ID;
    style.textContent = [
      '#' + CARD_ID + '{display:flex;align-items:center;gap:10px;width:100%;margin:0 0 12px;',
      'padding:12px 14px;border:1px solid var(--border,rgba(0,0,0,.08));border-radius:14px;',
      'background:var(--card,#fff);color:var(--foreground,#37352f);font:inherit;text-align:left;',
      'cursor:pointer;-webkit-tap-highlight-color:transparent;transition:background-color 140ms ease;}',
      '#' + CARD_ID + ':active{background:var(--muted,rgba(0,0,0,.04));}',
      '#' + CARD_ID + ' .dt-continue-icon{display:flex;align-items:center;justify-content:center;',
      'width:32px;height:32px;border-radius:10px;flex-shrink:0;',
      'background:var(--muted,rgba(0,0,0,.04));color:var(--muted-foreground,#8a8a8a);}',
      '#' + CARD_ID + ' .dt-continue-body{display:flex;flex-direction:column;gap:2px;',
      'min-width:0;flex:1;}',
      '#' + CARD_ID + ' .dt-continue-title{font-size:14px;font-weight:600;line-height:1.3;}',
      '#' + CARD_ID + ' .dt-continue-meta{font-size:12px;line-height:1.3;',
      'color:var(--muted-foreground,#8a8a8a);}',
    ].join('');
    (document.head || document.documentElement).appendChild(style);
  }

  function openSession(id) {
    var target = '/chat/' + id;
    var links = document.querySelectorAll('a[href]');
    for (var i = 0; i < links.length; i++) {
      var el = links[i];
      if (el.closest('#' + CARD_ID)) continue;
      var path = (el.getAttribute('href') || '').split('?')[0].split('#')[0];
      if (path === target) {
        el.click();
        return;
      }
    }
    location.assign(target);
  }

  function removeCard() {
    var card = document.getElementById(CARD_ID);
    if (card) card.remove();
  }

  function buildCard(last) {
    var card = document.createElement('button');
    card.id = CARD_ID;
    card.type = 'button';
    card.innerHTML =
      '<span class="dt-continue-icon"><svg viewBox="0 0 24 24" width="18" height="18" fill="none" ' +
      'stroke="currentColor" stroke-width="1.7" stroke-linecap="round" stroke-linejoin="round" ' +
      'aria-hidden="true"><path d="M3 12a9 9 0 1 0 3-6.7"/><path d="M3 4.5V9h4.5"/>' +
      '<path d="M12 8v4.2l2.8 1.7"/></svg></span>' +
      '<span class="dt-continue-body"><span class="dt-continue-title">继续上次对话</span>' +
      '<span class="dt-continue-meta" id="' + CARD_ID + '-meta"></span></span>';
    card.addEventListener('click', function () {
      openSession(last.id);
    });
    return card;
  }

  function mount() {
    if (location.pathname !== lastPath) {
      lastPath = location.pathname;
      remember();
    }
    var starters = document.querySelector('.dt-starters');
    if (!starters || location.pathname !== '/chat') {
      removeCard();
      return;
    }
    var last = readLast();
    if (!last) {
      removeCard();
      return;
    }
    ensureStyle();
    var meta = document.getElementById(CARD_ID + '-meta');
    if (meta) {
      meta.textContent = relativeTime(last.ts);
    }
    if (!document.getElementById(CARD_ID)) {
      starters.insertBefore(buildCard(last), starters.firstChild);
    }
  }

  function schedule() {
    if (timer) return;
    timer = setTimeout(function () {
      timer = null;
      try {
        mount();
      } catch (e) {}
    }, 300);
  }

  function start() {
    schedule();
    try {
      new MutationObserver(schedule).observe(document.body, { childList: true, subtree: true });
    } catch (e) {}
    // 路由变化不一定伴随 DOM 结构变化，低频轮询兜底
    setInterval(schedule, 1000);
  }

  if (document.readyState === 'loading') {
    document.addEventListener('DOMContentLoaded', start, { once: true });
  } else {
    start();
  }
})();

// 输入框补全提示：Web 端在空输入框上方显示「→ Tab 补全」，手机没有 Tab 键，
// 这里让这行文字本身可点，点击后执行与 Tab 键相同的补全。
(function () {
  if (window.__dtTabCompleteInstalled) return;
  window.__dtTabCompleteInstalled = true;

  var timer = null;

  function composerScope() {
    var input = document.querySelector('textarea');
    if (!input) return null;
    // 补全提示与 textarea 在同一个 composer 卡片内
    return input.closest('[class*="ChatComposer_surface"]') ||
      (input.parentElement && input.parentElement.parentElement) ||
      null;
  }

  function accept(span) {
    var input = document.querySelector('textarea');
    if (!input) return;
    var before = input.value;
    // 优先交给 Web 自己的 Tab 处理，保证补全后的状态与键盘操作一致
    try {
      input.dispatchEvent(new KeyboardEvent('keydown', {
        key: 'Tab',
        code: 'Tab',
        keyCode: 9,
        which: 9,
        bubbles: true,
        cancelable: true,
      }));
    } catch (e) {}
    if (input.value !== before) return;
    // 合成按键被忽略时，直接用提示文本写入输入框
    var suggestion = span.previousElementSibling;
    var text = suggestion ? (suggestion.textContent || '').trim() : '';
    if (!text) return;
    try {
      var setter = Object.getOwnPropertyDescriptor(HTMLTextAreaElement.prototype, 'value').set;
      setter.call(input, text);
      input.dispatchEvent(new Event('input', { bubbles: true }));
      input.focus();
      input.setSelectionRange(text.length, text.length);
    } catch (e2) {}
  }

  function mount() {
    var scope = composerScope();
    if (!scope) return;
    var spans = scope.querySelectorAll('span');
    for (var i = 0; i < spans.length; i++) {
      var el = spans[i];
      if (el.dataset.dtTabBound === '1') continue;
      var text = (el.textContent || '').trim();
      if (text.indexOf('→') !== 0 || text.indexOf('Tab') < 0) continue;
      el.dataset.dtTabBound = '1';
      el.style.pointerEvents = 'auto';
      el.style.cursor = 'pointer';
      // 负外边距抵消内边距，扩大触摸热区但不改变排版
      el.style.padding = '2px 4px';
      el.style.margin = '-2px -4px';
      el.addEventListener('click', function (event) {
        event.preventDefault();
        event.stopPropagation();
        accept(this);
      });
    }
  }

  function schedule() {
    if (timer) return;
    timer = setTimeout(function () {
      timer = null;
      try {
        mount();
      } catch (e) {}
    }, 300);
  }

  function start() {
    schedule();
    try {
      new MutationObserver(schedule).observe(document.body, { childList: true, subtree: true });
    } catch (e) {}
    setInterval(schedule, 1500);
  }

  if (document.readyState === 'loading') {
    document.addEventListener('DOMContentLoaded', start, { once: true });
  } else {
    start();
  }
})();

// 底部导航 dock：Web 端在窄屏下只把侧边栏变成抽屉，一级入口仍藏在左上角。
// 这里注入移动端常见的底部导航，隐藏页面顶部 44px 汉堡条，
// 把抽屉入口收进「更多」；软键盘或原生数学键盘打开时自动隐藏。
(function () {
  if (window.__dtDockInstalled) return;
  window.__dtDockInstalled = true;

  var DOCK_ID = 'dt-dock';
  var STYLE_ID = 'dt-dock-style';
  var DOCK_HEIGHT = 56;
  var MOBILE_MAX = 767.98;
  var KEYBOARD_THRESHOLD = 120;

  var TABS = [
    { href: '/chat', label: '首页', icon: '<path d="M3.5 10.4 12 3.6l8.5 6.8V20a1 1 0 0 1-1 1h-5v-6.3H9.5V21h-5a1 1 0 0 1-1-1z"/>' },
    { href: '/learning', label: '学习', icon: '<path d="M22 9.6 12 4.3 2 9.6l10 5.3 10-5.3z"/><path d="M6 12.3v4.6c0 1.8 2.7 3.2 6 3.2s6-1.4 6-3.2v-4.6"/>' },
    { href: '/space', label: '资源', icon: '<rect x="3.5" y="3.5" width="7" height="7" rx="1.7"/><rect x="13.5" y="3.5" width="7" height="7" rx="1.7"/><rect x="3.5" y="13.5" width="7" height="7" rx="1.7"/><rect x="13.5" y="13.5" width="7" height="7" rx="1.7"/>' },
    { href: '/partners', label: '伙伴', icon: '<circle cx="9" cy="8" r="3.2"/><path d="M2.9 20c0-3.1 2.7-5.1 6.1-5.1s6.1 2 6.1 5.1"/><path d="M16.3 5.2a3.2 3.2 0 0 1 0 6.2"/><path d="M17.5 15c2.1.6 3.6 2.1 3.6 4.3"/>' },
    { href: '/personalization', label: '个性化', icon: '<circle cx="10" cy="8.25" r="3.5"/><path d="M3.75 19.75c.55-3.35 3.15-5.6 6.25-5.6 1.2 0 2.32.33 3.27.92"/><path d="M17.75 12.75c.3 1.65 1.35 2.7 3 3-1.65.3-2.7 1.35-3 3-.3-1.65-1.35-2.7-3-3 1.65-.3 2.7-1.35 3-3z"/>' },
  ];
  var MORE_ICON =
    '<circle cx="5" cy="12" r="1.5" fill="currentColor" stroke="none"/>' +
    '<circle cx="12" cy="12" r="1.5" fill="currentColor" stroke="none"/>' +
    '<circle cx="19" cy="12" r="1.5" fill="currentColor" stroke="none"/>';

  var state = {
    keyboard: false,
    native: false,
    drawer: false,
    dock: null,
    bar: null,
    hamburger: null,
    hamburgerObserver: null,
    rootEl: null,
    rootSavedHeight: null,
    scheduled: false,
  };

  function isNarrow() {
    return (window.innerWidth || document.documentElement.clientWidth || 0) < MOBILE_MAX;
  }

  function ensureStyle() {
    if (document.getElementById(STYLE_ID)) return;
    var style = document.createElement('style');
    style.id = STYLE_ID;
    style.textContent = [
      '#' + DOCK_ID + '{position:fixed;left:0;right:0;bottom:0;height:' + DOCK_HEIGHT + 'px;display:flex;',
      'z-index:45;box-sizing:border-box;padding:0 4px;background:var(--background,#fff);',
      'border-top:1px solid var(--border,rgba(0,0,0,.08));',
      'transition:opacity 160ms ease,transform 160ms ease;',
      'font-family:inherit;-webkit-tap-highlight-color:transparent;}',
      '#' + DOCK_ID + '.is-hidden{opacity:0;transform:translateY(100%);pointer-events:none;}',
      '#' + DOCK_ID + '.is-off{display:none;}',
      '#' + DOCK_ID + ' .dt-dock-item{flex:1 1 0;min-width:0;display:flex;flex-direction:column;',
      'align-items:center;justify-content:center;gap:2px;margin:0;padding:0;border:0;',
      'background:transparent;color:var(--muted-foreground,#8a8a8a);font:inherit;font-size:10px;',
      'line-height:1.25;text-decoration:none;cursor:pointer;-webkit-user-select:none;user-select:none;}',
      '#' + DOCK_ID + ' .dt-dock-item svg{width:22px;height:22px;display:block;}',
      '#' + DOCK_ID + ' .dt-dock-item.is-active{color:var(--foreground,#37352f);}',
      '#' + DOCK_ID + ' .dt-dock-item.is-active span{font-weight:600;}',
      '#' + DOCK_ID + ' .dt-dock-item:active{opacity:.55;}',
      '@media (prefers-reduced-motion:reduce){#' + DOCK_ID + '{transition:none;}}',
    ].join('');
    (document.head || document.documentElement).appendChild(style);
  }

  /** 页面里是否存在可直接点击的站内链接，避免整页刷新。 */
  function findLink(href) {
    var links = document.querySelectorAll('a[href]');
    for (var i = 0; i < links.length; i++) {
      var el = links[i];
      if (el.closest('#' + DOCK_ID)) continue;
      var raw = el.getAttribute('href') || '';
      var path = raw.split('?')[0].split('#')[0];
      if (path === href) return el;
    }
    return null;
  }

  function navigate(href) {
    var path = location.pathname;
    // 首页是「新对话」入口：/chat/<id> 时点首页仍需回到空会话，不能按前缀判等
    var same = href === '/chat'
      ? path === '/chat'
      : (path === href || path.indexOf(href + '/') === 0);
    if (same) return;
    if (href === '/chat' && startNewChat()) {
      // 侧栏按钮无响应时（例如被 Web 改版去掉）回退整页跳转
      setTimeout(function () {
        if (location.pathname === path) location.assign('/chat');
      }, 500);
      return;
    }
    var link = findLink(href);
    if (!link) {
      location.assign(href);
      return;
    }
    var before = path;
    try {
      link.click();
    } catch (e) {}
    // 链接被拦截但路由没变（例如链接位于 inert 容器）时回退整页跳转
    setTimeout(function () {
      if (location.pathname === before) location.assign(href);
    }, 350);
  }

  /** 复用侧边栏的「新对话」按钮，避免整页刷新；找不到时返回 false。 */
  function startNewChat() {
    var buttons = document.querySelectorAll('button');
    for (var i = 0; i < buttons.length; i++) {
      var el = buttons[i];
      if (el.closest('#' + DOCK_ID)) continue;
      var text = (el.textContent || '').trim();
      if (text === '新对话' || text === 'New chat') {
        try {
          el.click();
          return true;
        } catch (e) {
          return false;
        }
      }
    }
    return false;
  }

  function buildDock() {
    var nav = document.createElement('nav');
    nav.id = DOCK_ID;
    nav.setAttribute('role', 'navigation');
    nav.setAttribute('aria-label', '主导航');
    TABS.forEach(function (tab) {
      var item = document.createElement('a');
      item.className = 'dt-dock-item';
      item.href = tab.href;
      item.setAttribute('data-dt-href', tab.href);
      item.innerHTML =
        '<svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.7" ' +
        'stroke-linecap="round" stroke-linejoin="round" aria-hidden="true">' + tab.icon + '</svg>' +
        '<span>' + tab.label + '</span>';
      item.addEventListener('click', function (event) {
        event.preventDefault();
        navigate(tab.href);
      });
      nav.appendChild(item);
    });
    var more = document.createElement('button');
    more.type = 'button';
    more.className = 'dt-dock-item';
    more.id = DOCK_ID + '-more';
    more.innerHTML =
      '<svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.7" ' +
      'stroke-linecap="round" stroke-linejoin="round" aria-hidden="true">' + MORE_ICON + '</svg>' +
      '<span>更多</span>';
    more.addEventListener('click', function () {
      if (state.hamburger) state.hamburger.click();
    });
    nav.appendChild(more);
    document.body.appendChild(nav);
    state.dock = nav;
    return nav;
  }

  function updateActive() {
    if (!state.dock) return;
    var path = location.pathname || '/';
    var items = state.dock.querySelectorAll('[data-dt-href]');
    for (var i = 0; i < items.length; i++) {
      var href = items[i].getAttribute('data-dt-href');
      var active = path === href || path.indexOf(href + '/') === 0 || (href === '/chat' && path === '/');
      items[i].classList.toggle('is-active', active);
      if (active) items[i].setAttribute('aria-current', 'page');
      else items[i].removeAttribute('aria-current');
    }
  }

  function findRoot() {
    var nodes = document.querySelectorAll('div.h-dvh');
    return nodes.length ? nodes[0] : null;
  }

  /** 压缩 Web 根容器高度，让内部滚动与聊天输入框整体上移，而不是被 dock 盖住。 */
  function setRootShift(on) {
    var el = findRoot();
    if (!el) return;
    if (el !== state.rootEl) {
      state.rootEl = el;
      state.rootSavedHeight = el.style.height || '';
    }
    if (on) {
      el.style.height = 'calc(100dvh - ' + DOCK_HEIGHT + 'px)';
    } else if (state.rootSavedHeight) {
      el.style.height = state.rootSavedHeight;
    } else {
      el.style.height = '';
    }
  }

  function bindDrawer() {
    if (state.hamburgerObserver) {
      state.hamburgerObserver.disconnect();
      state.hamburgerObserver = null;
    }
    var btn = state.hamburger;
    state.drawer = !!btn && btn.getAttribute('aria-expanded') === 'true';
    if (!btn) return;
    try {
      state.hamburgerObserver = new MutationObserver(function () {
        var open = btn.getAttribute('aria-expanded') === 'true';
        if (open === state.drawer) return;
        state.drawer = open;
        apply();
      });
      state.hamburgerObserver.observe(btn, { attributes: true, attributeFilter: ['aria-expanded'] });
    } catch (e) {}
  }

  /** 隐藏移动端顶栏（汉堡 + logo），汉堡按钮保留在 DOM 中供「更多」调用。 */
  function syncBar() {
    var main = document.querySelector('main');
    var bar = main && main.firstElementChild;
    if (!bar || String(bar.className || '').indexOf('md:hidden') < 0) return;
    if (state.bar !== bar) {
      state.bar = bar;
      state.hamburger = bar.querySelector('button');
      bindDrawer();
    }
    var shouldHide = isNarrow();
    var hidden = bar.style.display === 'none';
    if (shouldHide && !hidden) bar.style.display = 'none';
    else if (!shouldHide && hidden) bar.style.display = '';
  }

  function apply() {
    var hasMain = !!document.querySelector('main');
    var narrow = isNarrow();
    var visible = hasMain && narrow && !state.drawer && !state.keyboard && !state.native;
    if (state.dock) {
      state.dock.classList.toggle('is-off', !hasMain || !narrow);
      state.dock.classList.toggle('is-hidden', hasMain && narrow && !visible);
    }
    setRootShift(visible);
    updateActive();
  }

  function checkKeyboard() {
    var vv = window.visualViewport;
    var open = !!vv && (window.innerHeight - vv.height) > KEYBOARD_THRESHOLD;
    if (open === state.keyboard) return;
    state.keyboard = open;
    apply();
  }

  function sync() {
    state.scheduled = false;
    var hasMain = !!document.querySelector('main');
    if (!hasMain) {
      // 登录/注册等无 AppShell 的页面：不显示 dock，并撤销顶栏与高度改动
      if (state.bar && state.bar.style.display === 'none') state.bar.style.display = '';
      if (state.rootEl) setRootShift(false);
      if (state.dock) {
        state.dock.remove();
        state.dock = null;
      }
      return;
    }
    ensureStyle();
    if (!state.dock) buildDock();
    syncBar();
    apply();
  }

  function schedule() {
    if (state.scheduled) return;
    state.scheduled = true;
    requestAnimationFrame(sync);
  }

  // 原生侧（数学键盘面板）通过该入口抑制 dock，与软键盘共用同一套隐藏逻辑
  window.__dtSetDockSuppressed = function (suppressed) {
    state.native = !!suppressed;
    apply();
  };

  function start() {
    ensureStyle();
    schedule();
    try {
      new MutationObserver(schedule).observe(document.body, { childList: true, subtree: true });
    } catch (e) {}
    window.addEventListener('popstate', schedule);
    window.addEventListener('resize', function () {
      checkKeyboard();
      schedule();
    });
    if (window.visualViewport) {
      window.visualViewport.addEventListener('resize', checkKeyboard);
    }
    // Next 的 SPA 跳转走 history.pushState，不触发 popstate，这里轮询兜底
    var lastPath = location.pathname;
    setInterval(function () {
      if (location.pathname !== lastPath) {
        lastPath = location.pathname;
        schedule();
      }
    }, 500);
  }

  if (document.readyState === 'loading') {
    document.addEventListener('DOMContentLoaded', start, { once: true });
  } else {
    start();
  }
})();

