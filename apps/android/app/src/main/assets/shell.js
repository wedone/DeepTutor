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

