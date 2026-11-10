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
})();

