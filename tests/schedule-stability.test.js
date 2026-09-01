const assert = require('assert');
const childProcess = require('child_process');
const fs = require('fs');
const http = require('http');
const path = require('path');

const ROOT = path.resolve(__dirname, '..');
const CHROME = 'C:\\Program Files\\Google\\Chrome\\Application\\chrome.exe';
const OUTPUT = path.join(ROOT, 'build', 'visual-tests');
const fixture = {
  ok: true,
  syncedAt: '2026-09-01T16:30:00+08:00',
  term: { name: '2026-2027-1' },
  exams: [],
  sections: [],
  weekSchedules: [{
    week: { serialNumber: 1, name: '第1周', startDate: '2026-09-07', endDate: '2026-09-13' },
    items: [
      { courseName: 'FPGA原理及通信电路设计', dayOfWeek: 1, beginSection: 1, endSection: 2, beginTime: '08:00', endTime: '09:35', classroomName: '北院-爱特楼-103' },
      { courseName: '电磁场与电磁波A', dayOfWeek: 1, beginSection: 3, endSection: 5, beginTime: '09:55', endTime: '12:20', classroomName: '北院-爱特楼-103' },
      { courseName: '现代交换技术', dayOfWeek: 1, beginSection: 9, endSection: 10, beginTime: '16:45', endTime: '18:20', classroomName: '北院-学海楼-610', courseCode: '10115124282' },
      { courseName: '数字信号处理', dayOfWeek: 2, beginSection: 1, endSection: 2, beginTime: '08:00', endTime: '09:35', classroomName: '南湖-综合楼-201' },
      { courseName: '通信原理', dayOfWeek: 3, beginSection: 3, endSection: 4, beginTime: '09:55', endTime: '11:30', classroomName: '南湖-综合楼-202' },
      { courseName: '嵌入式系统', dayOfWeek: 4, beginSection: 5, endSection: 6, beginTime: '13:30', endTime: '15:05', classroomName: '鉴湖-主楼-301' }
    ]
  }]
};

function mime(file) {
  if (file.endsWith('.html')) return 'text/html; charset=utf-8';
  if (file.endsWith('.js')) return 'text/javascript; charset=utf-8';
  if (file.endsWith('.css')) return 'text/css; charset=utf-8';
  return 'application/octet-stream';
}

function startServer() {
  return new Promise(resolve => {
    const server = http.createServer((req, res) => {
      const pathname = decodeURIComponent(new URL(req.url, 'http://127.0.0.1').pathname);
      const file = path.resolve(ROOT, '.' + pathname);
      if (!file.startsWith(ROOT)) return res.writeHead(403).end('forbidden');
      fs.readFile(file, (error, data) => {
        if (error) return res.writeHead(404).end('not found');
        res.writeHead(200, { 'Content-Type': mime(file), 'Cache-Control': 'no-store' });
        res.end(data);
      });
    });
    server.listen(0, '127.0.0.1', () => resolve(server));
  });
}

function wait(ms) { return new Promise(resolve => setTimeout(resolve, ms)); }

async function waitForDebugPort(proc) {
  return new Promise((resolve, reject) => {
    let buffer = '';
    const timer = setTimeout(() => reject(new Error('Chrome DevTools 启动超时')), 12000);
    const onData = data => {
      buffer += data.toString();
      const match = buffer.match(/DevTools listening on ws:\/\/127\.0\.0\.1:(\d+)\//);
      if (!match) return;
      clearTimeout(timer);
      proc.stderr.off('data', onData);
      resolve(Number(match[1]));
    };
    proc.stderr.on('data', onData);
    proc.once('exit', code => {
      clearTimeout(timer);
      reject(new Error(`Chrome 提前退出：${code}\n${buffer}`));
    });
  });
}

class CdpClient {
  constructor(url) {
    this.ws = new WebSocket(url);
    this.seq = 0;
    this.pending = new Map();
  }
  async open() {
    await new Promise((resolve, reject) => {
      this.ws.onopen = resolve;
      this.ws.onerror = reject;
      this.ws.onmessage = event => {
        const msg = JSON.parse(event.data);
        if (!msg.id) return;
        const pending = this.pending.get(msg.id);
        if (!pending) return;
        this.pending.delete(msg.id);
        if (msg.error) pending.reject(new Error(msg.error.message));
        else pending.resolve(msg.result);
      };
    });
  }
  call(method, params = {}) {
    const id = ++this.seq;
    this.ws.send(JSON.stringify({ id, method, params }));
    return new Promise((resolve, reject) => this.pending.set(id, { resolve, reject }));
  }
  close() { this.ws.close(); }
}

async function evaluate(cdp, expression) {
  const result = await cdp.call('Runtime.evaluate', { expression, returnByValue: true, awaitPromise: true });
  if (result.exceptionDetails) {
    const detail = result.exceptionDetails.exception?.description || result.exceptionDetails.text || 'Runtime.evaluate failed';
    throw new Error(detail);
  }
  return result.result.value;
}

async function capture(cdp, fileName) {
  const shot = await cdp.call('Page.captureScreenshot', { format: 'png', fromSurface: true });
  fs.mkdirSync(OUTPUT, { recursive: true });
  fs.writeFileSync(path.join(OUTPUT, fileName), Buffer.from(shot.data, 'base64'));
}

async function setFixtureAndReload(cdp, dark = false) {
  const table = JSON.stringify(fixture);
  const prefs = JSON.stringify({ theme: 'blue', dark, reminders: true, minutes: 15 });
  await evaluate(cdp, `localStorage.setItem('whut-timetable-v1', ${JSON.stringify(table)}); localStorage.setItem('whut-ui-prefs-v1', ${JSON.stringify(prefs)}); 'ok'`);
  await cdp.call('Page.reload', { ignoreCache: true });
  await wait(450);
}

async function inspectSchedule(cdp) {
  return evaluate(cdp, `(() => {
    const cards = [...document.querySelectorAll('#course-list > .course-card')];
    const nav = document.querySelector('.bottom-nav').getBoundingClientRect();
    return {
      title: document.querySelector('#selected-date-title')?.textContent || '',
      subtitle: document.querySelector('#selected-date-subtitle')?.textContent || '',
      cardCount: cards.length,
      names: cards.map(card => card.querySelector('.course-title')?.textContent || ''),
      rects: cards.map(card => {
        const rect = card.getBoundingClientRect();
        const style = getComputedStyle(card);
        return { top: rect.top, bottom: rect.bottom, width: rect.width, height: rect.height, display: style.display, visibility: style.visibility, opacity: style.opacity };
      }),
      scrollWidth: document.documentElement.scrollWidth,
      navBottomGap: innerHeight - nav.bottom,
      dateChipCount: document.querySelectorAll('#date-strip > .date-chip').length,
      scrollY: window.scrollY,
      nativeWebview: document.body.classList.contains('native-webview'),
      bodyAnimation: getComputedStyle(document.body).animationName,
      bodyBeforeDisplay: getComputedStyle(document.body,'::before').display,
      bottomNavBackdrop: getComputedStyle(document.querySelector('.bottom-nav')).backdropFilter || getComputedStyle(document.querySelector('.bottom-nav')).webkitBackdropFilter || 'none',
      bottomNavBackground: getComputedStyle(document.querySelector('.bottom-nav')).backgroundImage,
      bottomNavBackgroundColor: getComputedStyle(document.querySelector('.bottom-nav')).backgroundColor,
      inactiveNavColor: getComputedStyle(document.querySelector('.nav-item:not(.active)')).color,
      nextCardBackdrop: getComputedStyle(document.querySelector('.next-card')).backdropFilter || getComputedStyle(document.querySelector('.next-card')).webkitBackdropFilter || 'none',
      liveEntryBackdrop: getComputedStyle(document.querySelector('.live-entry-card')).backdropFilter || getComputedStyle(document.querySelector('.live-entry-card')).webkitBackdropFilter || 'none',
      courseIsolation: cards[0] ? getComputedStyle(cards[0]).isolation : ''
    };
  })()`);
}

function assertMondayStable(state, width, label) {
  assert(state.title.includes('9月7日'), `${label}: 日期标题错误: ${state.title}`);
  assert.strictEqual(state.subtitle, '3 节课', `${label}: 课程数量标题错误`);
  assert.strictEqual(state.cardCount, 3, `${label}: DOM 课程卡数量不是 3`);
  assert.deepStrictEqual(state.names, ['FPGA原理及通信电路设计', '电磁场与电磁波A', '现代交换技术'], `${label}: 课程顺序/内容错误`);
  state.rects.forEach((rect, index) => {
    assert(rect.height >= 90, `${label}: 第 ${index + 1} 张课程卡高度异常 ${rect.height}`);
    assert(rect.width > width * 0.8, `${label}: 第 ${index + 1} 张课程卡宽度异常 ${rect.width}`);
    assert.strictEqual(rect.display, 'grid', `${label}: 第 ${index + 1} 张课程卡 display 异常`);
    assert.strictEqual(rect.visibility, 'visible', `${label}: 第 ${index + 1} 张课程卡被隐藏`);
    assert(Number(rect.opacity) > 0.9, `${label}: 第 ${index + 1} 张课程卡透明度异常`);
  });
  assert(state.rects[2].top > state.rects[1].bottom, `${label}: 第三张课程卡布局位置异常`);
  assert(state.scrollWidth <= width, `${label}: 页面出现横向溢出 ${state.scrollWidth}`);
  assert(state.navBottomGap >= 8 && state.navBottomGap <= 13, `${label}: Bottom Navigation 不在底部 ${state.navBottomGap}`);
  assert.strictEqual(state.dateChipCount, 7, `${label}: 日期节点数量异常`);
  assert.strictEqual(state.nativeWebview, true, `${label}: 未启用 Android WebView 稳定模式`);
  assert.strictEqual(state.bodyAnimation, 'none', `${label}: 原生 WebView 仍在运行持续背景动画`);
  assert.strictEqual(state.bodyBeforeDisplay, 'none', `${label}: fixed blur 背景层仍在原生 WebView 中参与合成`);
  assert.strictEqual(state.bottomNavBackdrop, 'none', `${label}: Bottom Navigation 仍启用 backdrop-filter`);
  assert(!/rgba\(/i.test(state.bottomNavBackground), `${label}: Bottom Navigation 渐变仍包含透明色: ${state.bottomNavBackground}`);
  assert(!/rgba\(/i.test(state.bottomNavBackgroundColor) && state.bottomNavBackgroundColor !== 'rgba(0, 0, 0, 0)', `${label}: Bottom Navigation 底色不是完全不透明: ${state.bottomNavBackgroundColor}`);
  assert(state.inactiveNavColor !== 'rgb(147, 158, 175)', `${label}: 未提高未选中页签文字对比度`);
  assert.strictEqual(state.nextCardBackdrop, 'none', `${label}: 下一节卡片仍启用 backdrop-filter`);
  assert.strictEqual(state.liveEntryBackdrop, 'none', `${label}: 直播入口仍启用 backdrop-filter`);
  assert.strictEqual(state.courseIsolation, 'auto', `${label}: 主课表课程卡仍额外创建 isolation stacking context`);
}

async function clickDay(cdp, day) {
  await evaluate(cdp, `document.querySelector('#date-strip > .date-chip[data-day="${day}"]').click(); 'ok'`);
}

async function swipeDateStrip(cdp, leftToRight) {
  const box = await evaluate(cdp, `(() => { const r=document.querySelector('#date-strip').getBoundingClientRect(); return {left:r.left,top:r.top,width:r.width,height:r.height}; })()`);
  const y = Math.round(box.top + box.height / 2);
  const startX = Math.round(leftToRight ? box.left + box.width * 0.25 : box.left + box.width * 0.75);
  const endX = Math.round(leftToRight ? box.left + box.width * 0.75 : box.left + box.width * 0.25);
  await cdp.call('Input.dispatchTouchEvent', { type: 'touchStart', touchPoints: [{ x: startX, y, radiusX: 4, radiusY: 4, force: 1 }] });
  for (let i = 1; i <= 4; i++) {
    const x = Math.round(startX + (endX - startX) * i / 4);
    await cdp.call('Input.dispatchTouchEvent', { type: 'touchMove', touchPoints: [{ x, y, radiusX: 4, radiusY: 4, force: 1 }] });
  }
  await cdp.call('Input.dispatchTouchEvent', { type: 'touchEnd', touchPoints: [] });
}

async function swipePageVertically(cdp, scrollDown) {
  const viewport = await evaluate(cdp, `({width:innerWidth,height:innerHeight})`);
  const x = Math.round(viewport.width * 0.52);
  const startY = Math.round(viewport.height * (scrollDown ? 0.76 : 0.28));
  const endY = Math.round(viewport.height * (scrollDown ? 0.28 : 0.76));
  await cdp.call('Input.dispatchTouchEvent', { type: 'touchStart', touchPoints: [{ x, y: startY, radiusX: 5, radiusY: 5, force: 1 }] });
  for (let i = 1; i <= 6; i++) {
    const y = Math.round(startY + (endY - startY) * i / 6);
    await cdp.call('Input.dispatchTouchEvent', { type: 'touchMove', touchPoints: [{ x, y, radiusX: 5, radiusY: 5, force: 1 }] });
  }
  await cdp.call('Input.dispatchTouchEvent', { type: 'touchEnd', touchPoints: [] });
  await wait(18);
}

async function stressVerticalScroll(cdp, width, screenshotSuffix = '') {
  await clickDay(cdp, 1);
  await evaluate(cdp, `window.scrollTo(0,0); window.__thirdCardProbe=document.querySelectorAll('#course-list > .course-card')[2]; 'ok'`);
  assertMondayStable(await inspectSchedule(cdp), width, `${width}px 垂直滑动前`);

  // 用户真实复现路径：不切日期，只在课表页反复上下滑动。30 轮 = 60 次纵向触摸手势。
  for (let round = 0; round < 30; round++) {
    await swipePageVertically(cdp, true);
    if (round % 5 === 0) assertMondayStable(await inspectSchedule(cdp), width, `${width}px 向下滑动第 ${round + 1} 轮`);
    await swipePageVertically(cdp, false);
    if (round % 5 === 0) assertMondayStable(await inspectSchedule(cdp), width, `${width}px 向上滑动第 ${round + 1} 轮`);
  }

  assert.strictEqual(await evaluate(cdp, `window.__thirdCardProbe===document.querySelectorAll('#course-list > .course-card')[2]`), true, `${width}px 纵向滑动期间第三张课程卡 DOM 被替换`);
  assertMondayStable(await inspectSchedule(cdp), width, `${width}px 上下滑动 60 次后`);

  // 再停在页面下部截图，专门检查第三张卡在滚动后的绘制状态。
  await swipePageVertically(cdp, true);
  await swipePageVertically(cdp, true);
  await wait(80);
  assertMondayStable(await inspectSchedule(cdp), width, `${width}px 滚动到底部后`);
  await capture(cdp, `schedule-vertical-scroll-${width}${screenshotSuffix}.png`);
  await evaluate(cdp, `window.scrollTo(0,0); 'ok'`);
  await wait(60);
  assertMondayStable(await inspectSchedule(cdp), width, `${width}px 回到顶部后`);
}

async function stressDates(cdp, width) {
  // 日期条是横向滚动层：切换日期只能更新选中态，不能销毁并重建正在滚动的节点。
  await evaluate(cdp, `window.__dateChipProbe=document.querySelector('#date-strip > .date-chip[data-day="1"]'); 'ok'`);

  // 快速 9/7 <-> 9/8，共 120 次日期切换。
  await evaluate(cdp, `(() => { for(let i=0;i<60;i++){ document.querySelector('#date-strip > .date-chip[data-day="2"]').click(); document.querySelector('#date-strip > .date-chip[data-day="1"]').click(); } return 'ok'; })()`);
  assert.strictEqual(await evaluate(cdp, `window.__dateChipProbe===document.querySelector('#date-strip > .date-chip[data-day="1"]')`), true, `${width}px 日期切换期间 date-strip 节点被重新创建`);
  assertMondayStable(await inspectSchedule(cdp), width, `${width}px 快速 120 次切换`);

  // 多日期往返 7 步 x 8 轮 = 56 次，带短等待模拟慢速点击。
  const route = [1, 2, 3, 4, 3, 2, 1];
  for (let round = 0; round < 8; round++) {
    for (const day of route) {
      await clickDay(cdp, day);
      await wait(12);
    }
  }
  assertMondayStable(await inspectSchedule(cdp), width, `${width}px 慢速/多日期 56 次切换`);

  // 真实触摸横向滚动日期条，再混合点击切换。
  for (let i = 0; i < 20; i++) {
    await swipeDateStrip(cdp, i % 2 === 0);
    await clickDay(cdp, i % 3 === 0 ? 2 : 1);
  }
  await clickDay(cdp, 1);
  await wait(80);
  assertMondayStable(await inspectSchedule(cdp), width, `${width}px 触摸滑动+点击混合`);

  // 切换 Bottom Navigation 后返回课表。
  for (const screen of ['today', 'exams', 'week', 'settings', 'schedule']) {
    await evaluate(cdp, `document.querySelector('[data-screen="${screen}"]').click(); 'ok'`);
    await wait(30);
  }
  await clickDay(cdp, 1);
  assertMondayStable(await inspectSchedule(cdp), width, `${width}px Bottom Navigation 往返`);
}

async function inspectModal(cdp) {
  return evaluate(cdp, `(() => {
    const modal=document.querySelector('.course-modal'), close=document.querySelector('#close-course-modal'), hero=document.querySelector('.course-modal-hero');
    const m=modal.getBoundingClientRect(), c=close.getBoundingClientRect(), h=hero.getBoundingClientRect();
    const cs=getComputedStyle(close), hs=getComputedStyle(hero);
    return {
      hidden: document.querySelector('#course-modal-backdrop').hidden,
      modal:{left:m.left,top:m.top,right:m.right,bottom:m.bottom},
      close:{left:c.left,top:c.top,right:c.right,bottom:c.bottom,width:c.width,height:c.height,position:cs.position,zIndex:Number(cs.zIndex)||0},
      hero:{left:h.left,top:h.top,right:h.right,bottom:h.bottom,zIndex:Number(hs.zIndex)||0},
      text: document.querySelector('#course-modal-content')?.innerText || ''
    };
  })()`);
}

function assertModalStable(modal, label) {
  assert.strictEqual(modal.hidden, false, `${label}: 详情弹窗没有打开`);
  assert(modal.text.includes('现代交换技术'), `${label}: 详情内容错误`);
  assert.strictEqual(modal.close.position, 'absolute', `${label}: 关闭按钮不是 absolute，可能又被通用玻璃规则覆盖`);
  assert(modal.close.width >= 36 && modal.close.height >= 36, `${label}: 关闭按钮尺寸异常`);
  assert(modal.close.left >= modal.modal.left + 10, `${label}: 关闭按钮越过弹窗左边界`);
  assert(modal.close.top >= modal.modal.top + 10, `${label}: 关闭按钮越过弹窗上边界`);
  assert(modal.close.right <= modal.modal.right - 10, `${label}: 关闭按钮越过弹窗右边界`);
  assert(modal.close.bottom <= modal.modal.bottom, `${label}: 关闭按钮越过弹窗下边界`);
  assert(modal.close.zIndex > modal.hero.zIndex, `${label}: 关闭按钮层级没有高于课程 Hero`);
}

async function stressModal(cdp, width, screenshotSuffix = '') {
  await clickDay(cdp, 1);
  for (let i = 0; i < 20; i++) {
    const cardIndex = i % 3;
    await evaluate(cdp, `document.querySelectorAll('#course-list > .course-card')[${cardIndex}].click(); 'ok'`);
    await wait(18);
    const modal = await inspectModal(cdp);
    assert.strictEqual(modal.hidden, false, `${width}px 第 ${i + 1} 次详情未打开`);
    if (cardIndex === 2) assertModalStable(modal, `${width}px 第 ${i + 1} 次现代交换技术详情`);
    await evaluate(cdp, `document.querySelector('#close-course-modal').click(); 'ok'`);
    await wait(10);
  }
  await clickDay(cdp, 1);
  assertMondayStable(await inspectSchedule(cdp), width, `${width}px 详情开关 20 次后`);

  await evaluate(cdp, `document.querySelectorAll('#course-list > .course-card')[2].click(); 'ok'`);
  await wait(80);
  assertModalStable(await inspectModal(cdp), `${width}px 现代交换技术最终详情`);
  await capture(cdp, `course-modal-${width}${screenshotSuffix}.png`);
  await evaluate(cdp, `document.querySelector('#close-course-modal').click(); 'ok'`);
}

(async () => {
  assert(fs.existsSync(CHROME), `未找到 Chrome: ${CHROME}`);
  const server = await startServer();
  const appUrl = `http://127.0.0.1:${server.address().port}/app/src/main/assets/index.html`;
  const userData = path.join(ROOT, 'build', 'chrome-stability-profile');
  fs.rmSync(userData, { recursive: true, force: true });
  const chrome = childProcess.spawn(CHROME, [
    '--headless=new', '--no-first-run', '--no-default-browser-check', '--remote-debugging-port=0',
    `--user-data-dir=${userData}`, appUrl
  ], { stdio: ['ignore', 'ignore', 'pipe'] });

  let cdp;
  try {
    const debugPort = await waitForDebugPort(chrome);
    await wait(250);
    const pages = await (await fetch(`http://127.0.0.1:${debugPort}/json/list`)).json();
    const page = pages.find(item => item.type === 'page' && item.url.includes('/app/src/main/assets/index.html')) || pages.find(item => item.type === 'page');
    assert(page?.webSocketDebuggerUrl, '没有找到可调试页面');
    cdp = new CdpClient(page.webSocketDebuggerUrl);
    await cdp.open();
    await cdp.call('Page.enable');
    await cdp.call('Runtime.enable');
    await cdp.call('Page.addScriptToEvaluateOnNewDocument', { source: `window.WhutBridge={getAppVersion(){return '1.6.5'},setDarkMode(){},saveNativeSchedule(){},configureReminders(){},requestNotificationPermission(){}};` });
    await cdp.call('Page.navigate', { url: appUrl });
    await wait(350);

    for (const width of [360, 390, 430]) {
      await cdp.call('Emulation.setDeviceMetricsOverride', { width, height: 900, deviceScaleFactor: 1, mobile: true });
      await cdp.call('Emulation.setTouchEmulationEnabled', { enabled: true, maxTouchPoints: 5 });
      await setFixtureAndReload(cdp, false);
      await clickDay(cdp, 1);
      assertMondayStable(await inspectSchedule(cdp), width, `${width}px 首次进入`);
      await stressVerticalScroll(cdp, width);
      await stressDates(cdp, width);
      await stressModal(cdp, width);
      await clickDay(cdp, 1);
      await capture(cdp, `schedule-stress-${width}.png`);
    }

    await cdp.call('Emulation.setDeviceMetricsOverride', { width: 390, height: 900, deviceScaleFactor: 1, mobile: true });
    await setFixtureAndReload(cdp, true);
    await clickDay(cdp, 1);
    assert.strictEqual(await evaluate(cdp, `document.body.classList.contains('dark')`), true, '深色模式未生效');
    await stressVerticalScroll(cdp, 390, '-dark');
    await stressDates(cdp, 390);
    await stressModal(cdp, 390, '-dark');
    await clickDay(cdp, 1);
    await capture(cdp, 'schedule-stress-390-dark.png');

    console.log('schedule-stability.test.js: native WebView fallback + 60 vertical swipes + 120 fast + 56 slow + touch swipe + nav + 20 modal + dark passed');
  } finally {
    try { cdp?.close(); } catch (_) {}
    server.close();
    chrome.kill();
  }
})().catch(error => {
  console.error(error.stack || error);
  process.exitCode = 1;
});
