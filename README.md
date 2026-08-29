# 武理课表

一个面向武汉理工大学教学管理系统的轻量 Android 课表 App。

> 本项目为个人学习/效率工具，**不是武汉理工大学官方应用**。

## 当前版本

`v1.3.0`

## 已完成功能

- Android 可安装 Release APK
- 武理统一认证登录
- 登录后自动读取本人当前学期课表
- 自动同步全部教学周
- 本地离线保存
- 独立课表、周览、今天页面
- 教学周切换
- 课程详情
- 考试安排
- 上课前提醒（5 / 10 / 15 / 30 / 60 分钟）
- Android 桌面“今日课程”小组件
- 课程手动添加、编辑、删除
- 深色模式
- 蓝 / 紫 / 绿 / 橙主题
- App 内检查更新
- 后台更新提醒
- 新版本 APK 自动下载并打开 Android 系统更新安装页
- 本地数据清除

## 安装

最终 APK：

`D:\武理课表\武理课表-v1.3.0.apk`

也可在 GitHub Release 中获取：

`https://github.com/927242768-dotcom/WHUT-Timetablespine/releases/latest`

后续版本继续使用同一应用签名，即可直接覆盖更新。

## 使用方法

1. 打开“武理课表”。
2. 点击同步按钮。
3. App 打开武汉理工大学官方教学管理系统。
4. 在学校网页中自行完成统一认证。
5. 登录成功进入教务系统后，App 自动识别登录状态并开始同步，无需再点击按钮。
6. App 自动读取当前学期、全部教学周、节次、课程与考试安排。
7. 同步完成后自动返回主界面。
8. 课表变化时重新同步即可。

## 更新机制

App 使用 GitHub Release 作为更新源：

`927242768-dotcom/WHUT-Timetablespine`

更新流程：

1. App 检查 `releases/latest`。
2. 比较当前版本与最新版本。
3. 发现新版后显示更新提醒。
4. 用户点击“立即更新”。
5. App 自动下载 APK。
6. Android 系统弹出安装更新确认页。

Android 不允许普通应用静默覆盖安装，因此最后一次系统确认需要用户手动完成。

## 隐私设计

- 用户名和密码由学校官方统一认证网页处理。
- App 不提供自己的密码输入框。
- App 不读取或保存统一认证密码。
- 同步后的课表数据保存在 Android App 私有目录及本地 WebView 存储中。
- 不包含第三方统计 SDK。
- 没有自建服务器上传课表数据。

## 教务数据接口

当前适配学校教学管理系统网页正在使用的接口：

- 当前用户：`api/home/currentUser.do`
- 学期：`api/home/kb/xnxq.do`
- 教学周：`api/home/getTermWeeks.do`
- 学生课表：`api/home/student/getMyScheduleDetail.do`
- 节次：`api/home/student/getSections.do`
- 考试安排：`api/home/student/exams.do`

接口由学校系统维护，如果以后教务系统改版，需要更新同步适配层。

## 工程结构

```text
app/src/main/java/com/whut/timetable/
  MainActivity.java          App 主界面与 Android 系统能力桥接
  ImportActivity.java        统一认证、课表和考试同步
  ReminderScheduler.java     上课提醒调度
  AlarmReceiver.java         课程提醒通知
  TodayWidgetProvider.java   桌面今日课程小组件
  UpdateManager.java         GitHub Release 更新检测与下载
  UpdateSchedule.java        定期检查更新
  UpdateCheckReceiver.java   后台更新检查
  BootReceiver.java          开机后恢复提醒与更新任务

app/src/main/assets/
  index.html                 App 页面
  style.css                  视觉、主题、深色模式
  app.js                     课表展示、考试、编辑与设置逻辑
```

## 已验证

- JavaScript 语法检查
- Android `assembleDebug`
- Android `assembleRelease`
- APK Signature Scheme v2 签名校验
- 360 / 390 / 430px 手机视口无页面级横向溢出
- 周览支持连续节次课程块并横向查看周末
- 课程详情采用彩色顶部与分组信息行

v1.3.0 APK SHA-256：

`66e47dfd33944b90502166d843e0a9055f02d034ed5d2f0cc46df42c8d43109d`
