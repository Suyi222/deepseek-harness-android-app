/**
 * 移动端软键盘适配 v0.2（deepdive 稳定版）
 * 原版方案（VisualViewport + translateY）保留，新增：
 *  - 竖屏同样生效（原 APK 锁横屏，解锁后本脚本在竖屏下也负责输入栏可见）
 *  - rAF 节流 + 异常保护，避免极端情况抖动
 *  - 暴露 --kb-height 供 CSS 使用
 * 注意：这是稳定基线版本，不含实验性 UI 变换（侧边栏/设置页改造另见后续版本）。
 */
(function () {
  if (!window.visualViewport) return;
  var vv = window.visualViewport;
  var app = document.getElementById('root') || document.body;
  var lastKb = 0;
  var rafId = 0;
  var raf = window.requestAnimationFrame || function (fn) { return setTimeout(fn, 16); };

  function computeKb() {
    // 键盘高度 ≈ 布局视口高度 - 视觉视口高度 - 视觉视口顶部偏移
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
      // 键盘弹出：把 App 容器向上平移，露出底部输入栏
      app.style.transform = 'translateY(' + (-kb) + 'px)';
      app.style.transition = 'transform 0.12s ease-out';
      document.documentElement.classList.add('kb-open');
    } else {
      // 键盘收起：恢复原位
      app.style.transform = '';
      app.style.transition = 'transform 0.12s ease-out';
      document.documentElement.classList.remove('kb-open');
    }
  }

  function schedule() {
    if (rafId) return;
    rafId = raf(function () {
      rafId = 0;
      apply();
    });
  }

  vv.addEventListener('resize', schedule);
  vv.addEventListener('scroll', schedule);
  window.addEventListener('resize', schedule);
  window.addEventListener('orientationchange', function () {
    // 旋转后等布局稳定再算一次
    setTimeout(schedule, 200);
  });
  document.addEventListener('focusin', function () {
    // 聚焦输入框后延迟一点再算，等键盘完全弹出
    setTimeout(schedule, 150);
  });
  document.addEventListener('focusout', function () {
    setTimeout(schedule, 150);
  });

  // 初始计算（等待首帧布局稳定）
  setTimeout(schedule, 100);
})();
