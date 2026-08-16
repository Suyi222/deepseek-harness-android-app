/**
 * deepdive 移动端适配 v0.5
 *  - 软键盘适配（VisualViewport + translateY）
 *  - 竖屏自动收起侧边栏（按文字找按钮）
 *  - 竖屏设置页「点击跳转」：菜单全宽 + 点击项目全屏内容 + 返回
 *    ★ v0.5 修复：MutationObserver 持续监听 DOM，React 重渲染后自动重新应用布局，
 *      不再"点几轮就退化"；事件委托避免节点重建丢监听
 */
(function () {
  var raf = window.requestAnimationFrame || function (fn) { return setTimeout(fn, 16); };

  /* ================= 软键盘适配 ================= */
  if (window.visualViewport) {
    var vv = window.visualViewport;
    var app = document.getElementById('root') || document.body;
    var lastKb = 0;
    var rafId = 0;

    function computeKb() {
      var kb = window.innerHeight - vv.height - vv.offsetTop;
      return Math.max(0, Math.round(kb));
    }

    function apply() {
      var kb = computeKb();
      if (Math.abs(kb - lastKb) < 6) return;
      lastKb = kb;
      document.documentElement.style.setProperty('--kb-height', kb + 'px');
      if (!app) return;
      if (kb > 120) {
        app.style.transform = 'translateY(' + (-kb) + 'px)';
        app.style.transition = 'transform 0.12s ease-out';
        document.documentElement.classList.add('kb-open');
      } else {
        app.style.transform = '';
        app.style.transition = 'transform 0.12s ease-out';
        document.documentElement.classList.remove('kb-open');
      }
    }

    function schedule() {
      if (rafId) return;
      rafId = raf(function () { rafId = 0; apply(); });
    }

    vv.addEventListener('resize', schedule);
    vv.addEventListener('scroll', schedule);
    window.addEventListener('resize', schedule);
    document.addEventListener('focusin', function () { setTimeout(schedule, 150); });
    document.addEventListener('focusout', function () { setTimeout(schedule, 150); });
    setTimeout(schedule, 100);
  }

  var isPortrait = function () {
    try { return window.matchMedia('(orientation: portrait)').matches; }
    catch (e) { return window.innerHeight > window.innerWidth; }
  };

  /* ================= 竖屏自动收起侧边栏 ================= */
  function collapseSidebarInPortrait() {
    try {
      if (!isPortrait()) return;
      var candidates = document.querySelectorAll(
        'button, [role="button"], [class*="collaps"], [class*="sidebar"], [title*="\u6536\u8d77"], [aria-label*="\u6536\u8d77"]'
      );
      var target = null;
      for (var i = 0; i < candidates.length; i++) {
        var el = candidates[i];
        var t = (el.textContent || '').trim();
        if (t.indexOf('\u6536\u8d77') >= 0 && t.length < 30) { target = el; break; }
        var title = el.getAttribute('title') || el.getAttribute('aria-label') || '';
        if (title.indexOf('\u6536\u8d77') >= 0) { target = el; break; }
      }
      if (target && !target.dataset.ddCollapsed) {
        target.dataset.ddCollapsed = '1';
        target.click();
      }
    } catch (e) { }
  }

  /* ================= 竖屏设置页「点击跳转」 ================= */
  var SETTINGS_MENU_TEXTS = ['\u901a\u7528\u8bbe\u7f6e', '\u6a21\u578b', '\u63d2\u4ef6', 'Agent \u9884\u8bbe'];
  var SETTINGS_CONTENT_MARKERS = ['\u6253\u5f00\u914d\u7f6e\u6587\u4ef6', '\u6743\u9650\u9009\u62e9', '\u5bf9\u6b64\u540e\u65b0\u5efa'];
  var ddSettings = null; // { menuCol, contentCol, backBtn }

  function containsAnyText(el, texts) {
    var t = el.textContent || '';
    for (var i = 0; i < texts.length; i++) {
      if (t.indexOf(texts[i]) >= 0) return true;
    }
    return false;
  }

  function findSettingsColumns() {
    var candidates = document.querySelectorAll('button, [role="button"], [class*="item"], [class*="tab"], li');
    var items = [];
    for (var i = 0; i < candidates.length; i++) {
      var el = candidates[i];
      var t = (el.textContent || '').trim();
      if (t.length > 0 && t.length < 14 && SETTINGS_MENU_TEXTS.indexOf(t) >= 0) {
        items.push(el);
      }
    }
    if (items.length < 2) return null;

    var menuCol = items[0];
    var guard = 0;
    while (menuCol && menuCol !== document.body && guard++ < 20) {
      var allInside = true;
      for (var j = 0; j < items.length; j++) {
        if (!menuCol.contains(items[j])) { allInside = false; break; }
      }
      if (allInside) {
        if (containsAnyText(menuCol, SETTINGS_CONTENT_MARKERS)) break;
        var parent = menuCol.parentElement;
        if (parent && containsAnyText(parent, SETTINGS_CONTENT_MARKERS)) break;
      }
      menuCol = menuCol.parentElement;
    }
    if (!menuCol || menuCol === document.body) return null;

    var contentCol = null;
    var sibs = menuCol.parentElement ? menuCol.parentElement.children : [];
    for (var k = 0; k < sibs.length; k++) {
      if (sibs[k] !== menuCol && containsAnyText(sibs[k], SETTINGS_CONTENT_MARKERS)) {
        contentCol = sibs[k]; break;
      }
    }
    if (!contentCol) return null;
    return { menuCol: menuCol, contentCol: contentCol };
  }

  function ensureBackButton(contentCol) {
    if (contentCol.querySelector('.dd-back-btn')) return;
    var back = document.createElement('button');
    back.className = 'dd-back-btn';
    back.textContent = '\u2190 \u8fd4\u56de';
    back.style.cssText = 'position:fixed;top:8px;left:8px;z-index:1000;padding:8px 16px;' +
      'background:rgba(31,39,51,0.92);color:#fff;border:1px solid rgba(120,140,180,0.4);' +
      'border-radius:10px;font-size:15px;cursor:pointer;';
    contentCol.appendChild(back);
  }

  // 幂等应用布局：React 重建节点后再次调用会重新应用
  function applySettingsLayout() {
    try {
      if (!isPortrait()) return;
      var cols = findSettingsColumns();
      if (!cols) return;
      var menuCol = cols.menuCol;
      var contentCol = cols.contentCol;

      if (menuCol.dataset.ddApplied !== '1') {
        menuCol.style.width = '100%';
        menuCol.style.maxWidth = '100%';
        menuCol.style.minWidth = '0';
        menuCol.dataset.ddApplied = '1';
      }
      // 内容列总是保持"隐藏 + fixed 全屏"（即使被 React 重建也重新设置）
      if (contentCol.dataset.ddOverlay !== '1') {
        contentCol.style.display = 'none';
        contentCol.style.position = 'fixed';
        contentCol.style.top = '0';
        contentCol.style.left = '0';
        contentCol.style.right = '0';
        contentCol.style.bottom = '0';
        contentCol.style.width = '100%';
        contentCol.style.height = '100%';
        contentCol.style.zIndex = '999';
        contentCol.style.overflowY = 'auto';
        contentCol.style.background = 'var(--dsw-alias-bg-base, #0b0f1a)';
        contentCol.dataset.ddOverlay = '1';
        contentCol.dataset.ddHidden = '1';
        ensureBackButton(contentCol);
      } else if (contentCol.dataset.ddHidden === '1') {
        contentCol.style.display = 'none';
      }

      ddSettings = { menuCol: menuCol, contentCol: contentCol };
    } catch (e) { }
  }

  // 事件委托：菜单项点击 → 显示内容；返回按钮点击 → 隐藏内容
  document.addEventListener('click', function (e) {
    try {
      if (!isPortrait() || !ddSettings) return;
      var el = e.target;
      var btn = el.closest ? el.closest('button, [role="button"]') : null;
      if (!btn) return;

      if (btn.classList && btn.classList.contains('dd-back-btn')) {
        var cc = ddSettings.contentCol;
        if (cc) { cc.style.display = 'none'; cc.dataset.ddHidden = '1'; }
        return;
      }
      var txt = (btn.textContent || '').trim();
      if (SETTINGS_MENU_TEXTS.indexOf(txt) >= 0) {
        var col = ddSettings.contentCol;
        if (col) {
          col.style.display = 'block';
          col.dataset.ddHidden = '0';
          col.scrollTop = 0;
        }
      }
    } catch (err) { }
  }, true);

  /* ================= 持续监听：React 重渲染后自动重新应用 ================= */
  var moTimer = 0;
  function scheduleReapply() {
    clearTimeout(moTimer);
    moTimer = setTimeout(function () {
      try {
        collapseSidebarInPortrait();
        applySettingsLayout();
      } catch (e) { }
    }, 250);
  }

  if (window.MutationObserver && document.body) {
    new MutationObserver(function () { scheduleReapply(); })
      .observe(document.body, { childList: true, subtree: true, attributes: true, attributeFilter: ['class', 'style'] });
  }

  // 兜底周期任务
  var tries = 0;
  setInterval(function () {
    try {
      collapseSidebarInPortrait();
      applySettingsLayout();
    } catch (e) { }
    if (++tries > 300) { /* 保持常驻，不清理 */ }
  }, 2000);

  window.addEventListener('orientationchange', function () {
    setTimeout(function () {
      collapseSidebarInPortrait();
      applySettingsLayout();
    }, 300);
  });

  // 初始应用
  setTimeout(function () {
    collapseSidebarInPortrait();
    applySettingsLayout();
  }, 500);
})();
