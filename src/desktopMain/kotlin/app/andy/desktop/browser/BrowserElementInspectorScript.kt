package app.andy.desktop.browser

/**
 * Injected into the visible WKWebView. Hover highlight + inspector card stay in-page
 * because the AppKit child window owns mouse events (Compose never sees them).
 */
internal const val BROWSER_ELEMENT_INSPECTOR_SCRIPT: String = """
(function() {
  if (window.__ANDY_ANNOTATE__ && window.__ANDY_ANNOTATE__.__installed) {
    return;
  }
  var ROOT_ID = '__andy-annotate-root';
  var state = {
    enabled: false,
    hovering: null,
    selected: null,
    composing: false
  };

  function root() {
    var el = document.getElementById(ROOT_ID);
    if (el) return el;
    el = document.createElement('div');
    el.id = ROOT_ID;
    el.setAttribute('data-andy-annotate', '1');
    var shadow = el.attachShadow({ mode: 'open' });
    shadow.innerHTML =
      '<style>' +
      '*{box-sizing:border-box;font-family:ui-sans-serif,system-ui,-apple-system,sans-serif;}' +
      '#hl{position:fixed;border:2px solid #5B9CFF;pointer-events:none;z-index:1;display:none;border-radius:2px;background:rgba(91,156,255,0.06);}' +
      '#tip{position:fixed;display:none;z-index:2;background:#1c1c1c;color:#f4f4f4;border:1px solid #3a3a3a;border-radius:8px;padding:8px 10px;min-width:180px;max-width:360px;font-size:11px;line-height:1.35;box-shadow:0 8px 24px rgba(0,0,0,.45);pointer-events:none;}' +
      '#tip .row{display:flex;justify-content:space-between;gap:16px;margin:0 0 4px;}' +
      '#tip .tag{font-family:ui-monospace,SFMono-Regular,Menlo,monospace;color:#fff;}' +
      '#tip .dim{font-family:ui-monospace,SFMono-Regular,Menlo,monospace;color:#c8c8c8;}' +
      '#tip .k{color:#8d8d8d;margin-right:8px;font-family:ui-monospace,SFMono-Regular,Menlo,monospace;}' +
      '#tip .v{color:#f4f4f4;font-family:ui-monospace,SFMono-Regular,Menlo,monospace;word-break:break-word;}' +
      '#form{position:fixed;display:none;z-index:3;align-items:center;gap:8px;background:#1a1a1a;border:1px solid #333;border-radius:999px;padding:4px 4px 4px 8px;box-shadow:0 10px 28px rgba(0,0,0,.5);min-width:280px;max-width:min(520px,calc(100vw - 24px));}' +
      '#badge{background:#163a66;color:#8ec5ff;font-family:ui-monospace,SFMono-Regular,Menlo,monospace;font-size:11px;padding:3px 8px;border-radius:999px;max-width:120px;overflow:hidden;text-overflow:ellipsis;white-space:nowrap;}' +
      '#input{flex:1;min-width:80px;background:transparent;border:0;outline:none;color:#f4f4f4;font-size:13px;padding:6px 4px;}' +
      '#input::placeholder{color:#8a8a8a;}' +
      '#send{width:28px;height:28px;border:0;border-radius:999px;background:#fff;color:#111;cursor:pointer;display:flex;align-items:center;justify-content:center;flex:0 0 auto;}' +
      '#send svg{display:block;}' +
      '</style>' +
      '<div id="hl"></div>' +
      '<div id="tip"></div>' +
      '<form id="form" autocomplete="off">' +
      '<span id="badge"></span>' +
      '<input id="input" type="text" placeholder="Add a comment..." />' +
      '<button id="send" type="submit" aria-label="Attach comment">' +
      '<svg width="14" height="14" viewBox="0 0 14 14" fill="none"><path d="M7 11.5V2.5M7 2.5L3.5 6M7 2.5L10.5 6" stroke="#111" stroke-width="1.6" stroke-linecap="round" stroke-linejoin="round"/></svg>' +
      '</button>' +
      '</form>';
    (document.documentElement || document.body).appendChild(el);
    var form = shadow.getElementById('form');
    form.addEventListener('submit', function(ev) {
      ev.preventDefault();
      ev.stopPropagation();
      submit();
    });
    return el;
  }

  function ui() {
    var r = root();
    var s = r.shadowRoot;
    return {
      hl: s.getElementById('hl'),
      tip: s.getElementById('tip'),
      form: s.getElementById('form'),
      badge: s.getElementById('badge'),
      input: s.getElementById('input')
    };
  }

  function isOurs(node) {
    while (node) {
      if (node.id === ROOT_ID) return true;
      if (node.getAttribute && node.getAttribute('data-andy-annotate') === '1') return true;
      node = node.parentNode;
    }
    return false;
  }

  function pickFromPoint(x, y) {
    var stack = document.elementsFromPoint(x, y);
    for (var i = 0; i < stack.length; i++) {
      var n = stack[i];
      if (!n || n === document.documentElement || n === document.body) continue;
      if (isOurs(n)) continue;
      return n;
    }
    return null;
  }

  function cssColor(value) {
    if (!value) return '';
    var ctx = document.createElement('canvas').getContext('2d');
    if (!ctx) return value;
    ctx.fillStyle = '#000';
    ctx.fillStyle = value;
    var computed = ctx.fillStyle;
    if (computed.charAt(0) === '#') return computed;
    var m = String(value).match(/rgba?\((\d+),\s*(\d+),\s*(\d+)/i);
    if (!m) return value;
    return '#' + [m[1], m[2], m[3]].map(function(p) {
      var h = Number(p).toString(16);
      return h.length === 1 ? '0' + h : h;
    }).join('');
  }

  function selectorFor(el) {
    if (!el || el.nodeType !== 1) return '';
    if (el.id) return el.tagName.toLowerCase() + '#' + el.id;
    var parts = [];
    var n = el;
    var hops = 0;
    while (n && n.nodeType === 1 && n !== document.documentElement && hops < 5) {
      var part = n.tagName.toLowerCase();
      if (n.classList && n.classList.length) {
        var cls = [];
        for (var i = 0; i < Math.min(2, n.classList.length); i++) cls.push(n.classList[i]);
        if (cls.length) part += '.' + cls.join('.');
      }
      parts.unshift(part);
      n = n.parentElement;
      hops++;
    }
    return parts.join(' > ');
  }

  function facts(el) {
    var cs = window.getComputedStyle(el);
    var rect = el.getBoundingClientRect();
    var text = (el.innerText || el.textContent || '').replace(/\s+/g, ' ').trim();
    if (text.length > 240) text = text.slice(0, 240);
    return {
      tag: el.tagName.toLowerCase(),
      selector: selectorFor(el),
      url: String(location.href || ''),
      title: String(document.title || ''),
      width: Math.round(rect.width),
      height: Math.round(rect.height),
      color: cssColor(cs.color),
      font: (cs.fontSize || '') + ' ' + (cs.fontFamily || ''),
      text: text,
      bounds: { x: rect.x, y: rect.y, w: rect.width, h: rect.height }
    };
  }

  function placeHighlight(el) {
    var box = ui();
    if (!el) {
      box.hl.style.display = 'none';
      box.tip.style.display = 'none';
      return;
    }
    var r = el.getBoundingClientRect();
    box.hl.style.display = 'block';
    box.hl.style.left = r.left + 'px';
    box.hl.style.top = r.top + 'px';
    box.hl.style.width = Math.max(0, r.width) + 'px';
    box.hl.style.height = Math.max(0, r.height) + 'px';
    var f = facts(el);
    box.tip.innerHTML =
      '<div class="row"><span class="tag">' + escapeHtml(f.tag) + '</span><span class="dim">' +
      f.width + '\u00d7' + f.height + '</span></div>' +
      (f.color ? '<div><span class="k">color</span><span class="v">' + escapeHtml(f.color) + '</span></div>' : '') +
      (f.font.trim() ? '<div><span class="k">font</span><span class="v">' + escapeHtml(f.font.trim()) + '</span></div>' : '');
    box.tip.style.display = 'block';
    var tipW = Math.min(360, Math.max(180, box.tip.offsetWidth || 220));
    var left = Math.min(Math.max(8, r.left), window.innerWidth - tipW - 8);
    var top = r.top - (box.tip.offsetHeight || 56) - 8;
    if (top < 8) top = r.bottom + 8;
    box.tip.style.left = left + 'px';
    box.tip.style.top = top + 'px';
  }

  function escapeHtml(s) {
    return String(s).replace(/[&<>"']/g, function(c) {
      return ({ '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;' })[c];
    });
  }

  function showForm(el) {
    state.selected = el;
    state.composing = true;
    var box = ui();
    var f = facts(el);
    box.badge.textContent = f.tag;
    box.input.value = '';
    box.form.style.display = 'flex';
    var r = el.getBoundingClientRect();
    var w = Math.min(520, Math.max(280, r.width));
    box.form.style.width = w + 'px';
    var left = r.left + (r.width / 2) - (w / 2);
    left = Math.min(Math.max(8, left), window.innerWidth - w - 8);
    var top = r.bottom - 18;
    if (top + 44 > window.innerHeight) top = r.top - 44;
    box.form.style.left = left + 'px';
    box.form.style.top = top + 'px';
    placeHighlight(el);
    box.tip.style.display = 'none';
    setTimeout(function() { box.input.focus(); }, 0);
  }

  function hideForm() {
    state.composing = false;
    state.selected = null;
    var box = ui();
    box.form.style.display = 'none';
    box.input.value = '';
  }

  function post(payload) {
    try {
      if (window.webkit && window.webkit.messageHandlers && window.webkit.messageHandlers.andyAnnotate) {
        window.webkit.messageHandlers.andyAnnotate.postMessage(payload);
      }
    } catch (err) { /* ignore */ }
  }

  function submit() {
    if (!state.selected) return;
    var box = ui();
    var comment = (box.input.value || '').trim();
    var f = facts(state.selected);
    post({
      type: 'submit',
      comment: comment,
      tag: f.tag,
      selector: f.selector,
      url: f.url,
      title: f.title,
      width: f.width,
      height: f.height,
      color: f.color,
      font: f.font.trim(),
      text: f.text,
      bounds: f.bounds
    });
    setEnabled(false);
  }

  function onMove(ev) {
    if (!state.enabled || state.composing) return;
    var el = pickFromPoint(ev.clientX, ev.clientY);
    state.hovering = el;
    placeHighlight(el);
  }

  function consume(ev) {
    ev.preventDefault();
    ev.stopPropagation();
    ev.stopImmediatePropagation();
  }

  function onClick(ev) {
    if (!state.enabled) return;
    if (state.composing) {
      var path = ev.composedPath ? ev.composedPath() : [];
      for (var i = 0; i < path.length; i++) {
        if (path[i] && path[i].id === 'form') return;
      }
      consume(ev);
      return;
    }
    var el = pickFromPoint(ev.clientX, ev.clientY);
    if (!el) return;
    consume(ev);
    showForm(el);
  }

  function onKey(ev) {
    if (!state.enabled) return;
    if (ev.key === 'Escape' || ev.key === 'Esc') {
      ev.preventDefault();
      if (state.composing) {
        hideForm();
        return;
      }
      setEnabled(false);
      post({ type: 'cancel' });
    }
  }

  function destroy() {
    state.enabled = false;
    state.hovering = null;
    state.selected = null;
    state.composing = false;
    document.documentElement.style.cursor = '';
    window.removeEventListener('mousemove', onMove, true);
    window.removeEventListener('pointerdown', onClick, true);
    window.removeEventListener('click', onClick, true);
    window.removeEventListener('keydown', onKey, true);
    var el = document.getElementById(ROOT_ID);
    if (el && el.parentNode) el.parentNode.removeChild(el);
    try { delete window.__ANDY_ANNOTATE__; } catch (err) { window.__ANDY_ANNOTATE__ = undefined; }
  }

  function setEnabled(enabled) {
    if (!enabled) {
      destroy();
      return;
    }
    state.enabled = true;
    var box = ui();
    document.documentElement.style.cursor = 'crosshair';
    box.hl.style.cursor = 'crosshair';
  }

  window.addEventListener('mousemove', onMove, true);
  window.addEventListener('pointerdown', onClick, true);
  window.addEventListener('click', onClick, true);
  window.addEventListener('keydown', onKey, true);

  window.__ANDY_ANNOTATE__ = {
    __installed: true,
    setEnabled: setEnabled,
    destroy: destroy
  };
})();
"""

internal const val BROWSER_ELEMENT_INSPECTOR_TEARDOWN_SCRIPT: String =
    "window.__ANDY_ANNOTATE__&&window.__ANDY_ANNOTATE__.destroy&&window.__ANDY_ANNOTATE__.destroy();"