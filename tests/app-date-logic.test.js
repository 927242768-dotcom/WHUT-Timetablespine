const assert = require('assert');
const fs = require('fs');
const path = require('path');
const vm = require('vm');

const appPath = path.join(__dirname, '..', 'app', 'src', 'main', 'assets', 'app.js');
const originalSource = fs.readFileSync(appPath, 'utf8');
const tail = '  initializeSelection();applyAppearance();bindEvents();renderAll();syncNativeSnapshot();\n})();';
const injectedTail = '  window.__testHooks={findScheduleForDate,upcomingStudyItems,studyDayLabel,currentWeekLoads,renderToday,renderExams,itemsFor,courseDateFor,liveDateLabel,memoItemsFor,saveMemoItems};\n})();';
assert(originalSource.includes(tail), '无法注入 app.js 测试钩子');
const source = originalSource.replace(tail, injectedTail);

class FakeElement {
  constructor() {
    this.innerHTML = '';
    this.textContent = '';
    this.children = [];
    this.dataset = {};
    this.hidden = false;
    this.value = '';
    this.checked = false;
    this.className = '';
    this.style = { setProperty() {} };
    this.classList = { add() {}, remove() {}, toggle() {}, contains() { return false; } };
  }
  appendChild(child) { this.children.push(child); return child; }
  querySelector() { return null; }
  querySelectorAll() { return []; }
  addEventListener() {}
  focus() {}
}

function makeFixture(weekStart = '2026-09-07', weekEnd = '2026-09-13', items = null) {
  return {
    ok: true,
    term: { name: '2026-2027-1' },
    exams: [],
    sections: [],
    weekSchedules: [{
      week: { serialNumber: 1, name: '第1周', startDate: weekStart, endDate: weekEnd },
      items: items || [
        { courseName: 'FPGA原理及通信电路设计', dayOfWeek: 1, beginSection: 1, endSection: 2, beginTime: '08:00', endTime: '09:35', classroomName: '北院-爱特楼-103' },
        { courseName: '电磁场与电磁波A', dayOfWeek: 1, beginSection: 3, endSection: 4, beginTime: '09:55', endTime: '11:30', classroomName: '北院-爱特楼-103' },
        { courseName: '现代交换技术', dayOfWeek: 1, beginSection: 9, endSection: 10, beginTime: '16:45', endTime: '18:20', classroomName: '北院-学海楼-610' }
      ]
    }]
  };
}

function boot(fixture) {
  const elements = new Map();
  const localData = new Map([['whut-timetable-v1', JSON.stringify(fixture)]]);
  const document = {
    getElementById(id) {
      if (!elements.has(id)) elements.set(id, new FakeElement());
      return elements.get(id);
    },
    createElement() { return new FakeElement(); },
    querySelectorAll() { return []; },
    documentElement: { style: { setProperty() {} }, dataset: {} },
    body: new FakeElement()
  };
  const localStorage = {
    getItem(key) { return localData.has(key) ? localData.get(key) : null; },
    setItem(key, value) { localData.set(key, String(value)); },
    removeItem(key) { localData.delete(key); }
  };
  const context = {
    console,
    document,
    localStorage,
    setTimeout(fn) { fn(); return 1; },
    clearTimeout() {},
    requestAnimationFrame(fn) { fn(); },
    Date,
    Math,
    JSON,
    String,
    Number,
    Array,
    Object,
    Map,
    Set
  };
  context.window = context;
  context.window.addEventListener = () => {};
  context.window.scrollTo = () => {};
  vm.createContext(context);
  vm.runInContext(source, context, { filename: 'app.js' });
  return { hooks: context.__testHooks, elements, localData };
}

function textOfChildren(element) {
  return element.children.map(child => child.innerHTML || child.textContent || '').join('\n');
}

// 场景 A：9/1 不属于第一教学周；Today 不得把 9/7 冒充今天。
{
  const { hooks, elements } = boot(makeFixture());
  const now = new Date(2026, 8, 1, 14, 0, 0);
  assert.strictEqual(hooks.findScheduleForDate(now), null);
  const next = hooks.upcomingStudyItems(3, now);
  assert.strictEqual(next.length, 3);
  assert.strictEqual(next[0].date.getFullYear(), 2026);
  assert.strictEqual(next[0].date.getMonth(), 8);
  assert.strictEqual(next[0].date.getDate(), 7);
  assert.strictEqual(hooks.studyDayLabel(next[0].date, next[0].day, now), '9月7日 · 周一');
  hooks.renderToday(now);
  const summary = elements.get('today-summary').innerHTML;
  const dashboard = elements.get('today-list');
  assert(summary.includes('今天没有课程'));
  assert(summary.includes('今天可以轻松一点'));
  assert(!summary.includes('教学周日期范围'));
  assert(!summary.includes('不会再用'));
  const dashboardText = textOfChildren(dashboard);
  assert(dashboardText.includes('今日备忘'));
  assert(dashboardText.includes('下一次课程'));
  assert(dashboardText.includes('9月7日 · 周一'));
  assert(dashboardText.includes('FPGA原理及通信电路设计'));
}

// 场景 B：当前日期属于教学周，但当天确实没课；下一次课程应指向未来真实日期。
{
  const items = [
    { courseName: '通信原理', dayOfWeek: 3, beginSection: 1, endSection: 2, beginTime: '08:00', endTime: '09:35', classroomName: '南湖-综合楼-201' }
  ];
  const { hooks, elements } = boot(makeFixture('2026-09-07', '2026-09-13', items));
  const now = new Date(2026, 8, 8, 12, 0, 0);
  assert(hooks.findScheduleForDate(now));
  hooks.renderToday(now);
  assert(elements.get('today-summary').innerHTML.includes('今天没有课程'));
  const dashboardText = textOfChildren(elements.get('today-list'));
  assert(dashboardText.includes('明天 · 9月9日'));
  assert(dashboardText.includes('通信原理'));
}

// 场景 C：当天真的有课；Today 只显示当天真实课程。
{
  const { hooks, elements } = boot(makeFixture());
  const now = new Date(2026, 8, 7, 7, 0, 0);
  hooks.renderToday(now);
  assert(elements.get('today-summary').innerHTML.includes('今天有 3 节课'));
  const dashboard = elements.get('today-list');
  assert.strictEqual(dashboard.children.length, 4); // 3 个课程卡 + 今日备忘
  assert(dashboard.children[0].innerHTML.includes('FPGA原理及通信电路设计'));
  assert(!textOfChildren(dashboard).includes('下一次课程'));
}

// 场景 D：无考试时，“接下来”按真实日期排序，且右侧不再固定写“今天”。
{
  const { hooks, elements } = boot(makeFixture());
  const now = new Date(2026, 8, 1, 14, 0, 0);
  hooks.renderExams(now);
  const html = elements.get('exam-list').innerHTML;
  assert(html.includes('接下来'));
  assert(html.includes('查看课表'));
  assert(html.includes('9月7日 · 周一 · 08:00 · 北院-爱特楼-103'));
  assert(!html.includes('data-go="today"'));
  assert.deepStrictEqual(Array.from(hooks.currentWeekLoads(now)), [0, 0, 0, 0, 0, 0, 0]);
}

// 今日备忘必须按日期隔离保存。
{
  const { hooks, localData } = boot(makeFixture());
  const sep1 = new Date(2026, 8, 1, 10, 0, 0);
  const sep2 = new Date(2026, 8, 2, 10, 0, 0);
  hooks.saveMemoItems(sep1, [{ id: 'a', text: '完成实验报告', done: false }]);
  hooks.saveMemoItems(sep2, [{ id: 'b', text: '复习通信原理', done: true }]);
  assert.strictEqual(hooks.memoItemsFor(sep1)[0].text, '完成实验报告');
  assert.strictEqual(hooks.memoItemsFor(sep2)[0].text, '复习通信原理');
  const saved = JSON.parse(localData.get('whut-daily-memos-v1'));
  assert.strictEqual(saved['2026-09-01'][0].id, 'a');
  assert.strictEqual(saved['2026-09-02'][0].id, 'b');
}

// 直播课堂标签继续以真实时间戳判定今天/明天/更远日期。
{
  const { hooks } = boot(makeFixture());
  const now = new Date(2026, 8, 1, 9, 0, 0);
  const tomorrowTs = Math.floor(new Date(2026, 8, 2, 10, 0, 0).getTime() / 1000);
  const futureTs = Math.floor(new Date(2026, 8, 7, 10, 0, 0).getTime() / 1000);
  assert.strictEqual(hooks.liveDateLabel({ startAt: tomorrowTs }, now), '明天');
  assert.strictEqual(hooks.liveDateLabel({ startAt: futureTs }, now), '9月7日 · 周一');
}

console.log('app-date-logic.test.js: all scenarios passed');
