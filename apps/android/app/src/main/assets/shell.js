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
