(function () {
  'use strict';
  if (window.__dtMathBridge) return;
  window.__dtMathBridge = true;

  var BASE = 'https://appassets.androidplatform.net/assets/mathlive/';
  var panel = null;
  var field = null;
  var mod = null;
  var loading = null;
  var lastTextarea = null;

  document.addEventListener('focusin', function (e) {
    var el = e.target;
    if (el && el.tagName === 'TEXTAREA') lastTextarea = el;
  }, true);

  function isDark() {
    return document.documentElement.classList.contains('dark');
  }

  function ensureButton() {
    if (!document.body || document.getElementById('dt-math-trigger')) return;
    var btn = document.createElement('button');
    btn.id = 'dt-math-trigger';
    btn.type = 'button';
    btn.textContent = 'Σ';
    btn.setAttribute('aria-label', '数学公式');
    var dark = isDark();
    btn.style.cssText =
      'position:fixed;right:12px;bottom:160px;width:44px;height:44px;border-radius:50%;border:none;' +
      'background:' + (dark ? 'rgba(96,148,255,.92)' : 'rgba(79,140,255,.92)') + ';color:#fff;' +
      'font-size:20px;font-weight:600;line-height:44px;padding:0;text-align:center;' +
      'box-shadow:0 4px 12px rgba(0,0,0,.28);z-index:2147483000;';
    btn.addEventListener('click', function (e) {
      e.preventDefault();
      e.stopPropagation();
      openPanel();
    });
    document.body.appendChild(btn);
  }

  function removeButton() {
    var btn = document.getElementById('dt-math-trigger');
    if (btn) btn.remove();
  }

  function applyTheme() {
    if (!panel) return;
    var dark = isDark();
    panel.style.background = dark ? '#1b1d22' : '#ffffff';
    panel.style.color = dark ? '#f1f2f4' : '#111111';
    panel.style.borderTop = '1px solid ' + (dark ? '#2a2d34' : '#e6e6e6');
  }

  function buildPanel() {
    if (panel) return panel;
    panel = document.createElement('div');
    panel.id = 'dt-math-panel';
    panel.style.cssText =
      'position:fixed;left:0;right:0;bottom:0;z-index:2147483001;display:none;' +
      'border-top-left-radius:16px;border-top-right-radius:16px;' +
      'box-shadow:0 -8px 24px rgba(0,0,0,.25);padding:12px 12px 16px;' +
      'font-family:system-ui,-apple-system,"Segoe UI",sans-serif;';

    var head = document.createElement('div');
    head.style.cssText = 'display:flex;align-items:center;justify-content:space-between;margin-bottom:8px;';
    var title = document.createElement('span');
    title.textContent = '数学公式';
    title.style.cssText = 'font-size:13px;opacity:.65;';
    var close = document.createElement('button');
    close.type = 'button';
    close.textContent = '关闭';
    close.style.cssText = 'background:none;border:none;font-size:13px;opacity:.65;padding:4px 6px;';
    close.addEventListener('click', closePanel);
    head.appendChild(title);
    head.appendChild(close);

    var host = document.createElement('div');
    host.style.cssText = 'border-radius:10px;padding:6px;min-height:56px;';
    host.id = 'dt-math-host';

    var actions = document.createElement('div');
    actions.style.cssText = 'display:flex;gap:8px;margin-top:10px;';
    var kbBtn = document.createElement('button');
    kbBtn.type = 'button';
    kbBtn.textContent = '软键盘';
    kbBtn.style.cssText =
      'flex:1;height:40px;border-radius:10px;border:1px solid rgba(128,128,128,.4);background:transparent;' +
      'color:inherit;font-size:14px;';
    kbBtn.addEventListener('click', function () {
      if (mod && mod.mathVirtualKeyboard) mod.mathVirtualKeyboard.toggle();
    });
    var insertBtn = document.createElement('button');
    insertBtn.type = 'button';
    insertBtn.textContent = '插入';
    insertBtn.style.cssText =
      'flex:1;height:40px;border-radius:10px;border:none;background:#4f8cff;color:#fff;font-size:14px;';
    insertBtn.addEventListener('click', insertLatex);
    actions.appendChild(kbBtn);
    actions.appendChild(insertBtn);

    panel.appendChild(head);
    panel.appendChild(host);
    panel.appendChild(actions);
    document.body.appendChild(panel);
    applyTheme();
    return panel;
  }

  function loadMathLive() {
    if (mod) return Promise.resolve(mod);
    if (loading) return loading;
    loading = import(BASE + 'mathlive.mjs').then(function (m) {
      m.MathfieldElement.fontsDirectory = BASE + 'fonts';
      mod = m;
      return m;
    }).catch(function (err) {
      loading = null;
      console.warn('[DeepTutor] mathlive load failed', err);
      throw err;
    });
    return loading;
  }

  function openPanel() {
    buildPanel();
    panel.style.display = 'block';
    applyTheme();
    if (lastTextarea) lastTextarea.blur();
    loadMathLive().then(function (m) {
      if (field) return;
      var host = panel.querySelector('#dt-math-host');
      field = document.createElement('math-field');
      field.style.cssText = 'display:block;width:100%;min-height:48px;font-size:20px;';
      field.setAttribute('math-virtual-keyboard-policy', 'manual');
      host.appendChild(field);
      setTimeout(function () {
        try { field.focus(); } catch (e) {}
      }, 60);
    }).catch(function () {
      var host = panel.querySelector('#dt-math-host');
      if (host) host.textContent = '公式组件加载失败';
    });
  }

  function closePanel() {
    if (field) {
      try { field.blur(); } catch (e) {}
    }
    if (panel) panel.style.display = 'none';
    if (lastTextarea) {
      try { lastTextarea.focus(); } catch (e) {}
    }
  }

  function insertLatex() {
    if (!field) return;
    var latex = String(field.value || '').trim();
    if (!latex) return;
    var el = (lastTextarea && document.contains(lastTextarea)) ? lastTextarea : document.querySelector('textarea');
    if (!el) return;

    var value = el.value || '';
    var start = typeof el.selectionStart === 'number' ? el.selectionStart : value.length;
    var end = typeof el.selectionEnd === 'number' ? el.selectionEnd : start;
    var snippet = '$' + latex + '$';
    var next = value.slice(0, start) + snippet + value.slice(end);

    var setter = Object.getOwnPropertyDescriptor(window.HTMLTextAreaElement.prototype, 'value').set;
    setter.call(el, next);
    el.dispatchEvent(new Event('input', { bubbles: true }));

    var caret = start + snippet.length;
    try {
      el.focus();
      el.setSelectionRange(caret, caret);
    } catch (e) {}

    try { field.value = ''; } catch (e) {}
    closePanel();
  }

  function sync() {
    if (!document.body) return;
    if (document.querySelector('textarea')) ensureButton();
    else removeButton();
  }

  var scheduled = false;
  function scheduleSync() {
    if (scheduled) return;
    scheduled = true;
    requestAnimationFrame(function () {
      scheduled = false;
      sync();
    });
  }

  function boot() {
    sync();
    new MutationObserver(scheduleSync).observe(document.documentElement, { childList: true, subtree: true });
  }

  // 旧内核走 onPageFinished 注入时已错过 DOMContentLoaded
  if (document.readyState === 'loading') document.addEventListener('DOMContentLoaded', boot);
  else boot();
})();
