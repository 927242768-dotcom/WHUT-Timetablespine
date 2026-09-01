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
  syncedAt: '2026-09-01T13:30:00+08:00',
  term: { name: '2026-2027-1' },
  exams: [],
  sections: [],
  weekSchedules: [{
    week: { serialNumber: 1, name: '第1周', startDate: '2026-09-07', endDate: '2026-09-13' },
    items: [
      { courseName: 'FPGA原理及通信电路设计', dayOfWeek: 1, beginSection: 1, endSection: 2, beginTime: '08:00', endTime: '09:35', classroomName: '北院-爱特楼-103' },
      { courseName: '电磁场与电磁波A', dayOfWeek: 1, beginSection: 3, endSection: 4, beginTime: '09:55', endTime: '11:30', classroomName: '北院-爱特楼-103' },
      { courseName: '现代交换技术', dayOfWeek: 1, beginSection: 9, endSection: 10, beginTime: '16:45', endTime: '18:20', classroomName: '北院-学海楼-610' }
    ]
  }]
};

function mime(file) {
  if (file.endsWith('.html')) return 'text/html; charset=utf-8';
  if (file.endsWith('.js')) return 'text/javascript; charset=utf-8';
  if (file.endsWith('.css')) return 'text/css; charset=utf-8';
  if (file.endsWith('.svg')) return 'image/svg+xml';
  return 'application/octet-stream';
}

function startServer() {
  return new Promise(resolve => {
    const server = http.createServer((req, res) => {
      const pathname = decodeURIComponent(new URL(req.url, 'http://127.0.0.1').pathname);
      const file = path.resolve(ROOT, '.' + pathname);
      if (!file.startsWith(ROOT)) {
        res.writeHead(403).end('forbidden');
        return;
      }
      fs.readFile(file, (error, data) => {
        if (error) {
          res.writeHead(404).end('not found');
          return;
        }
        res.writeHead(200, { 'Content-Type': mime(file), 'Cache-Control': 'no-store' });
        res.end(data);
      });
    });
    server.listen(0, '127.0.0.1', () => resolve(server));
  });
}

function wait(ms) { return new Promise(resolve => setTimeout(resolve, ms)); }

async function waitForDebugPort(proc) {
  return await new Promise((resolve, reject) => {
    let buffer = '';
    const timer = setTimeout(() => reject(new Error('Chrome DevTools 启动超时')), 12000);
    const onData = data => {
      buffer += data.toString();
      const match = buffer.match(/DevTools listening on ws:\/\/127\.0\.0\.1:(\d+)\//);
      if (match) {
        clearTimeout(timer);
        proc.stderr.off('data', onData);
        resolve(Number(match[1]));
      }
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
  if (result.exceptionDetails) throw new Error(result.exceptionDetails.text || 'Runtime.evaluate failed');
  return result.result.value;
}

async function capture(cdp, fileName) {
  const shot = await cdp.call('Page.captureScreenshot', { format: 'png', fromSurface: true });
  fs.mkdirSync(OUTPUT, { recursive: true });
  fs.writeFileSync(path.join(OUTPUT, fileName), Buffer.from(shot.data, 'base64'));
}

async function setFixtureAndReload(cdp, dark) {
  const table = JSON.stringify(fixture);
  const prefs = JSON.stringify({ theme: 'blue', dark, reminders: true, minutes: 15 });
  await evaluate(cdp, `localStorage.setItem('whut-timetable-v1', ${JSON.stringify(table)}); localStorage.setItem('whut-ui-prefs-v1', ${JSON.stringify(prefs)}); 'ok'`);
  await cdp.call('Page.reload', { ignoreCache: true });
  await wait(650);
}

async function inspectScreen(cdp, screen) {
  await evaluate(cdp, `document.querySelector('[data-screen="${screen}"]').click(); 'ok'`);
  await wait(220);
  return await evaluate(cdp, `(() => {
    const nav = document.querySelector('.bottom-nav').getBoundingClientRect();
    const active = document.querySelector('.screen.active').getBoundingClientRect();
    return {
      innerWidth,
      innerHeight,
      scrollWidth: document.documentElement.scrollWidth,
      navBottomGap: Math.round(innerHeight - nav.bottom),
      navTop: Math.round(nav.top),
      activeLeft: Math.round(active.left),
      activeRight: Math.round(active.right),
      bodyDark: document.body.classList.contains('dark'),
      todaySummary: document.querySelector('#today-summary')?.innerText || '',
      todayText: document.querySelector('#today-list')?.innerText || '',
      examText: document.querySelector('#exam-list')?.innerText || ''
    };
  })()`);
}

(async () => {
  assert(fs.existsSync(CHROME), `未找到 Chrome: ${CHROME}`);
  const server = await startServer();
  const serverPort = server.address().port;
  const appUrl = `http://127.0.0.1:${serverPort}/app/src/main/assets/index.html`;
  const userData = path.join(ROOT, 'build', 'chrome-visual-profile');
  fs.rmSync(userData, { recursive: true, force: true });
  const chrome = childProcess.spawn(CHROME, [
    '--headless=new',
    '--disable-gpu',
    '--no-first-run',
    '--no-default-browser-check',
    '--remote-debugging-port=0',
    `--user-data-dir=${userData}`,
    appUrl
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

    for (const width of [360, 390, 430]) {
      await cdp.call('Emulation.setDeviceMetricsOverride', { width, height: 900, deviceScaleFactor: 1, mobile: true });
      await setFixtureAndReload(cdp, false);
      const metrics = await inspectScreen(cdp, 'today');
      assert.strictEqual(metrics.innerWidth, width, `${width}px viewport 未生效`);
      assert(metrics.scrollWidth <= width, `${width}px 出现横向溢出: ${metrics.scrollWidth}`);
      assert(metrics.navBottomGap >= 9 && metrics.navBottomGap <= 12, `${width}px Bottom Navigation 未固定到底部: gap=${metrics.navBottomGap}`);
      assert(metrics.activeLeft >= 0 && metrics.activeRight <= width, `${width}px Today 页面越界`);
      assert(metrics.todaySummary.includes('今天没有课程'), `${width}px Today 空状态错误`);
      assert(!metrics.todaySummary.includes('教学周日期范围'), `${width}px 仍存在调试文案`);
      assert(metrics.todayText.includes('今日备忘'), `${width}px 未显示今日备忘`);
      assert(metrics.todayText.includes('下一次课程'), `${width}px 未显示下一次课程`);
      assert(metrics.todayText.includes('9月7日 · 周一'), `${width}px 下一次课程日期错误`);
      await capture(cdp, `today-${width}.png`);
    }

    await cdp.call('Emulation.setDeviceMetricsOverride', { width: 390, height: 900, deviceScaleFactor: 1, mobile: true });
    await setFixtureAndReload(cdp, true);
    const darkMetrics = await inspectScreen(cdp, 'today');
    assert.strictEqual(darkMetrics.bodyDark, true, '深色模式没有生效');
    assert(darkMetrics.scrollWidth <= 390, '深色模式 Today 出现横向溢出');
    await capture(cdp, 'today-390-dark.png');

    await setFixtureAndReload(cdp, false);
    const examMetrics = await inspectScreen(cdp, 'exams');
    assert(examMetrics.examText.includes('接下来'), '考试页没有“接下来”区域');
    assert(examMetrics.examText.includes('查看课表'), '考试页右上角没有改成“查看课表”');
    assert(examMetrics.examText.includes('9月7日 · 周一'), '考试页“接下来”没有真实日期');
    assert(!examMetrics.examText.includes('\n今天\n'), '考试页“接下来”仍固定显示“今天”');
    assert(examMetrics.scrollWidth <= 390, '考试页出现横向溢出');
    await capture(cdp, 'exams-390.png');

    console.log('visual-layout.test.js: 360 / 390 / 430 + dark mode + exams passed');
  } finally {
    try { cdp?.close(); } catch (_) {}
    server.close();
    chrome.kill();
  }
})().catch(error => {
  console.error(error.stack || error);
  process.exitCode = 1;
});
