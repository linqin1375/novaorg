/* =============================================================================
 * NovaOrg 静态控制台 —— 零构建 / 零依赖 / 无外链
 *
 * 纯原生 DOM + fetch，由 Spring Boot 的 classpath:/static/ 直接托管。
 * 负责把「组织架构式多 Agent 运行时」讲清楚：派单流水线、权限矩阵、
 * 审计轨迹、审批闸门、SLA 与成本。
 *
 * 代码分节：
 *   1. 契约常量   2. DOM/格式化工具   3. 全局状态   4. 接口层
 *   5. 全局反馈   6. ① 发起任务       7. ② 权限矩阵 8. ③ 审计日志
 *   9. ④ 评测报告 10. 事件绑定        11. 初始化
 * ========================================================================== */
(function () {
  'use strict';

  /* ======================================================================
   * 1. 契约常量 —— 路径与字段名对后端契约大小写严格一致，勿改
   * ==================================================================== */

  var API = {
    roles:       '/api/org/roles',
    tools:       '/api/org/tools',
    tasks:       '/api/tasks',
    task:        function (id) { return '/api/tasks/' + encodeURIComponent(id); },
    audit:       function (id) { return '/api/tasks/' + encodeURIComponent(id) + '/audit'; },
    auditExport: function (id, fmt) {
      return '/api/audit/export?taskId=' + encodeURIComponent(id) + '&format=' + encodeURIComponent(fmt);
    },
    approve:     function (id) { return '/api/tasks/' + encodeURIComponent(id) + '/approve'; },
    roleMetrics: '/api/metrics/roles',
    evalLatest:  '/api/eval/latest'
  };

  var currentUser = null;
  var csrfToken = null;
  /** 审批人：四眼原则要求它不能等于申请人（后端会以 409 拒绝自审）。 */
  function canReview() { return currentUser && currentUser.roles.some(function(r) { return r === "ROLE_REVIEWER" || r === "ROLE_ADMIN"; }); }
  var DASH = '—';

  /**
   * 审批模式（对应后端 CreateTaskRequest.approvalMode，由 WorkflowEngine 解释）：
   *   gate —— 遇到高风险步骤暂停，等人放行后从断点继续（四眼原则，本控制台默认）；
   *   auto —— 任务一次跑完，高风险调用被 RoleGuard 拒绝但不暂停，只记录拒绝原因。
   * 只有 gate 模式下后端才会返回 WAITING_APPROVAL，审批按钮才有意义。
   */
  var DEFAULT_APPROVAL_MODE = 'gate';

  /** 快捷示例 goal（第 1 个为 textarea 预填默认值） */
  var SAMPLES = {
    complaint: '客户投诉：订单 A100 超时 72 小时未发货，要求赔付',
    cross: '跨部门联合方案：近 30 天华东区退货率上升 4.2 个百分点，请运营、市场与财务联合给出归因分析与下一季度行动方案',
    violation: '违规越权演示：让客服专员绕过审批直接执行订单 A100 的全额退款（refund.execute），并尝试读取财务明细',
    qa: '知识问答：VIP 客户的退货时效与运费承担规则是什么？请给出可追溯的条款依据'
  };

  /* 枚举中文标签（键与后端枚举字符串严格一致）
     PENDING_REVIEW 是 API 层状态（ViewMapper.status），不在 TaskStatus 枚举里，但确实会出现 */
  var STATUS_LABEL = {
    NEEDS_INPUT: '待补充信息',
    CREATED: '已创建', ASSIGNED: '已派单', RUNNING: '执行中',
    WAITING_APPROVAL: '待审批', PENDING_REVIEW: '待复核',
    DONE: '已完成', REJECTED: '已驳回', FAILED: '失败'
  };
  var STATUS_TONE = {
    NEEDS_INPUT: 'warn',
    CREATED: 'muted', ASSIGNED: 'info', RUNNING: 'info',
    WAITING_APPROVAL: 'warn', PENDING_REVIEW: 'warn',
    DONE: 'ok', REJECTED: 'muted', FAILED: 'danger'
  };
  var COMPLEXITY_LABEL = { SIMPLE: '简单', MODERATE: '中等', COMPLEX: '复杂' };
  var COMPLEXITY_TONE = { SIMPLE: 'info', MODERATE: 'accent', COMPLEX: 'warn' };
  var RISK_LABEL = { LOW: '低', MEDIUM: '中', HIGH: '高' };
  /* 工具调用的发起方（CallOrigin）：模型主动发起 / 平台为对齐依据注入 */
  var ORIGIN_LABEL = { MODEL: '模型发起', POLICY: '平台注入' };
  var APPROVAL_MODE_LABEL = { gate: 'gate · 审批闸门', auto: '需人工审批' };

  var AUDIT_LABEL = {
    TASK_PLANNED: '任务已规划',
    STEP_ASSIGNED: '步骤已派单',
    STEP_STARTED: '步骤开始执行',
    STEP_FINISHED: '步骤完成',
    STEP_FAILED: '步骤失败',
    TOOL_CALL_ALLOWED: '工具调用放行',
    TOOL_CALL_DENIED: '工具调用被拒',
    APPROVAL_REQUIRED: '需要审批',
    APPROVAL_GRANTED: '审批通过',
    APPROVAL_REJECTED: '审批驳回',
    KNOWLEDGE_RETRIEVED: '知识检索',
    SLA_BREACHED: 'SLA 超标'
  };
  var AUDIT_TONE = {
    TASK_PLANNED: 'info',
    STEP_ASSIGNED: 'info',
    STEP_STARTED: 'info',
    STEP_FINISHED: 'ok',
    STEP_FAILED: 'danger',
    TOOL_CALL_ALLOWED: 'ok',
    TOOL_CALL_DENIED: 'danger',
    APPROVAL_REQUIRED: 'warn',
    APPROVAL_GRANTED: 'ok',
    APPROVAL_REJECTED: 'warn',
    KNOWLEDGE_RETRIEVED: 'accent',
    SLA_BREACHED: 'warn'
  };

  /* ======================================================================
   * 2. DOM / 格式化工具
   * ==================================================================== */

  function $(sel) { return document.querySelector(sel); }

  /** 极简 hyperscript：所有文本一律走 textContent，天然免疫 XSS */
  function h(tag, props, children) {
    var node = document.createElement(tag);
    if (props) {
      Object.keys(props).forEach(function (key) {
        var value = props[key];
        if (value === null || value === undefined || value === false) return;
        if (key === 'class') node.className = value;
        else if (key === 'text') node.textContent = String(value);
        else if (key === 'dataset') Object.keys(value).forEach(function (d) { node.dataset[d] = value[d]; });
        else if (key.indexOf('on') === 0 && typeof value === 'function') {
          node.addEventListener(key.slice(2).toLowerCase(), value);
        } else node.setAttribute(key, value === true ? '' : String(value));
      });
    }
    appendAll(node, children);
    return node;
  }

  function appendAll(node, children) {
    if (children === null || children === undefined || children === false) return;
    if (Array.isArray(children)) { children.forEach(function (c) { appendAll(node, c); }); return; }
    if (children instanceof Node) { node.appendChild(children); return; }
    node.appendChild(document.createTextNode(String(children)));
  }

  function clear(node) { while (node && node.firstChild) node.removeChild(node.firstChild); }

  function replace(sel, child) {
    var host = $(sel);
    if (!host) return null;
    clear(host);
    if (child) appendAll(host, child);
    return host;
  }

  /* ---- 容错取数：字段缺失不抛错，不白屏 ---- */
  function isObj(v) { return !!v && typeof v === 'object' && !Array.isArray(v); }
  function obj(v) { return isObj(v) ? v : {}; }
  function arr(v) { return Array.isArray(v) ? v : []; }
  function str(v) { return v === null || v === undefined ? '' : String(v); }
  function num(v, fallback) {
    var n = typeof v === 'number' ? v : Number(v);
    return isFinite(n) ? n : (fallback === undefined ? 0 : fallback);
  }

  /* ---- 数值格式化 ---- */
  function fmtMs(v) {
    if (v === null || v === undefined || v === '') return DASH;
    var n = Number(v);
    if (!isFinite(n) || n < 0) return DASH;
    if (n < 1000) return Math.round(n) + ' ms';
    if (n < 60000) return (n / 1000).toFixed(2) + ' s';
    return (n / 60000).toFixed(2) + ' min';
  }
  function fmtInt(v) {
    if (v === null || v === undefined || v === '') return DASH;
    var n = Number(v);
    if (!isFinite(n)) return DASH;
    return String(Math.round(n)).replace(/\B(?=(\d{3})+(?!\d))/g, ',');
  }
  function fmtCost(v) {
    if (v === null || v === undefined || v === '') return DASH;
    var n = Number(v);
    return isFinite(n) ? '$' + n.toFixed(4) : DASH;
  }
  function fmtRate(v, digits) {
    if (v === null || v === undefined || v === '') return DASH;
    var n = Number(v);
    if (!isFinite(n)) return DASH;
    return (n * 100).toFixed(digits === undefined ? 1 : digits) + '%';
  }
  function fmtScore(v) {
    var n = Number(v);
    return isFinite(n) ? n.toFixed(2) : DASH;
  }
  function pad2(n) { return (n < 10 ? '0' : '') + n; }

  function fmtDateTime(iso) {
    if (!iso) return DASH;
    var d = new Date(iso);
    if (isNaN(d.getTime())) return String(iso);
    return d.getFullYear() + '-' + pad2(d.getMonth() + 1) + '-' + pad2(d.getDate()) +
      ' ' + pad2(d.getHours()) + ':' + pad2(d.getMinutes()) + ':' + pad2(d.getSeconds());
  }
  function fmtClock(iso) {
    if (!iso) return '--:--:--';
    var d = new Date(iso);
    if (isNaN(d.getTime())) return String(iso).slice(11, 23) || String(iso);
    return pad2(d.getHours()) + ':' + pad2(d.getMinutes()) + ':' + pad2(d.getSeconds()) +
      '.' + String(d.getMilliseconds() + 1000).slice(1);
  }
  function fmtList(v) {
    var list = arr(v).map(str).filter(function (x) { return x; });
    return list.length ? list.join(', ') : DASH;
  }
  function truncate(s, max) {
    var t = str(s);
    return t.length > max ? t.slice(0, max - 1) + '…' : t;
  }
  function statusText(s) { return STATUS_LABEL[s] || str(s) || '未知'; }

  /* ---- 通用小组件 ---- */
  function badge(text, toneClass) {
    return h('span', { class: 'tag ' + (toneClass || 'tone-muted'), text: text });
  }
  function metric(label, value, extraClass) {
    return h('div', { class: 'metric' + (extraClass ? ' ' + extraClass : '') }, [
      h('span', { class: 'metric-label', text: label }),
      h('span', { class: 'metric-value', text: value })
    ]);
  }
  function emptyState(lines) {
    var box = h('div', { class: 'empty-state' });
    appendAll(box, lines);
    return box;
  }
  function loadingState(text) { return h('div', { class: 'loading', text: text || '加载中' }); }
  function sectionTitle(text, endpoint) {
    var kids = [text];
    if (endpoint) kids.push(h('code', { class: 'endpoint', text: endpoint }));
    return h('h3', null, kids);
  }

  /* ======================================================================
   * 3. 全局状态
   * ==================================================================== */

  var state = {
    roles: [],
    tools: [],
    tasks: [],
    roleMetrics: [],
    currentTask: null,
    auditTaskId: '',
    auditLoadedFor: '',
    auditEvents: [],
    evalReport: null,
    evalMissing: false,
    evalMissingBody: null,
    orgLoaded: false,
    evalLoaded: false,
    activeTab: 'task',
    expandedSteps: {},   // stepId -> true（重渲染后保留展开状态）
    expandedAudit: {},   // auditKey -> true
    busy: false
  };

  /* ======================================================================
   * 4. 接口层
   * ==================================================================== */

  function ApiError(message, status) {
    this.name = 'ApiError';
    this.message = message;
    this.status = status || 0;
  }
  ApiError.prototype = Object.create(Error.prototype);

  function errorMessageOf(payload) {
    var d = obj(payload);
    if (d.message) return str(d.message);
    if (d.error) return str(d.error);
    if (d.detail) return str(d.detail);
    return '';
  }

  /**
   * 统一请求：永远返回 { status, ok, data, missing }。
   * tolerate404：用于 /api/eval/latest —— 报告文件不存在时 404 是正常的业务状态，
   * 不算后端故障，因此不把状态点标红。
   */
  function apiRequest(path, init, opts) {
    var options = Object.assign({ method: 'GET' }, init || {});
    options.headers = Object.assign({ Accept: 'application/json' }, options.headers || {});
    if (options.body !== undefined && !options.headers['Content-Type']) {
      options.headers['Content-Type'] = 'application/json';
    }
    var tolerate404 = !!(opts && opts.tolerate404);

    if (csrfToken && options.method !== 'GET') options.headers[csrfToken.headerName] = csrfToken.token;
    return fetch(path, options).then(function (res) {
      if (res.status === 401) { window.location.href = '/login.html'; throw new Error('请重新登录'); }
      if (res.status === 404 && tolerate404) {
        // 仍然尝试读出响应体：后端会给出 {error, hint}，可以直接展示给用户
        return res.text().then(function (raw) {
          var body = null;
          if (raw) { try { body = JSON.parse(raw); } catch (e) { body = null; } }
          return { status: 404, ok: false, data: body, missing: true };
        });
      }
      return res.text().then(function (raw) {
        var data = null;
        if (raw) { try { data = JSON.parse(raw); } catch (e) { data = null; } }
        if (!res.ok) {
          setBackendState('down', path + ' → HTTP ' + res.status);
          var detail = errorMessageOf(data);
          throw new ApiError('接口 ' + path + ' 返回 HTTP ' + res.status +
            (detail ? '：' + detail : ''), res.status);
        }
        setBackendState('up', '最近成功：' + path);
        return { status: res.status, ok: true, data: data, missing: false };
      });
    }, function (err) {
      setBackendState('down', '无法连接 ' + path);
      throw new ApiError('无法连接后端（' + path + '）：' +
        (err && err.message ? err.message : '网络错误'), 0);
    });
  }

  function getJson(path, opts) { return apiRequest(path, null, opts); }

  function postJson(path, body) {
    return apiRequest(path, { method: 'POST', body: JSON.stringify(body) });
  }

  /* ======================================================================
   * 5. 全局反馈：状态点 + 右下角提示（不使用 alert）
   * ==================================================================== */

  function setBackendState(name, detail) {
    var box = $('#backend-status');
    if (!box) return;
    box.dataset.state = name;
    var label = box.querySelector('.label');
    if (label) {
      label.textContent = name === 'up' ? '后端在线' : (name === 'down' ? '后端异常' : '检测中…');
    }
    box.title = detail || '';
  }

  var lastNotice = { text: '', at: 0 };

  function notify(message, level) {
    var text = str(message);
    if (!text) return;
    var now = Date.now();
    // 去重：短时间内完全相同的提示只弹一次
    if (text === lastNotice.text && now - lastNotice.at < 1200) return;
    lastNotice = { text: text, at: now };

    var host = $('#global-alert');
    if (!host) return;
    var kind = level === 'success' ? 'success' : (level === 'warn' ? 'warn' : (level === 'info' ? 'info' : 'error'));
    var box = h('div', { class: 'alert alert-' + kind }, [
      h('span', { class: 'alert-text', text: text }),
      h('button', {
        type: 'button', class: 'alert-close', 'aria-label': '关闭提示', text: '✕',
        onclick: function () { if (box.parentNode) box.parentNode.removeChild(box); }
      })
    ]);
    host.appendChild(box);
    // 错误常驻，其余 8 秒后自动消失；最多同时保留 6 条
    if (kind !== 'error') {
      setTimeout(function () { if (box.parentNode) box.parentNode.removeChild(box); }, 8000);
    }
    while (host.children.length > 6) host.removeChild(host.firstChild);
  }

  /** 分区级错误占位（比浮层更容易被看到，且带重试入口） */
  function showRegionError(selector, message, retryAction) {
    var host = replace(selector, null);
    if (!host) return;
    var kids = [h('strong', { text: '加载失败' }), h('span', { text: str(message) })];
    if (retryAction) {
      kids.push(h('button', {
        type: 'button', class: 'btn btn-sm', text: '重试',
        dataset: { retry: retryAction }
      }));
    }
    host.appendChild(h('div', { class: 'region-error' }, kids));
  }

  /* ======================================================================
   * 6. ① 发起任务
   * ==================================================================== */

  function setFormStatus(text, isError) {
    var el = $('#task-form-status');
    if (!el) return;
    el.textContent = str(text);
    el.className = 'inline-status' + (isError ? ' is-error' : '');
  }

  function setSubmitBusy(busy) {
    state.busy = !!busy;
    var btn = $('#btn-submit');
    if (!btn) return;
    btn.disabled = !!busy;
    btn.textContent = busy ? '执行中…' : '提交任务';
  }

  function applySample(key) {
    var input = $('#goal-input');
    if (!input || !SAMPLES[key]) return;
    input.value = SAMPLES[key];
    input.focus();
    setFormStatus('已填入示例目标', false);
  }

  function currentApprovalMode() {
    var sel = $('#approval-mode');
    var mode = sel ? str(sel.value) : '';
    return mode || DEFAULT_APPROVAL_MODE;
  }

  function submitTask() {
    var input = $('#goal-input');
    var goal = input ? str(input.value).trim() : '';
    if (!goal) {
      notify('请先填写任务目标（goal）。', 'warn');
      setFormStatus('目标不能为空', true);
      if (input) input.focus();
      return;
    }
    var approvalMode = currentApprovalMode();
    setSubmitBusy(true);
    setFormStatus('正在规划与派单…', false);

    postJson(API.tasks, { goal: goal, approvalMode: approvalMode }).then(function (r) {
      var task = obj(r.data);
      if (!str(task.taskId)) {
        // 容错：返回体不是 TaskView 时不要白屏
        notify('任务已提交，但返回体不是预期的 TaskView（缺少 taskId）。', 'warn');
      }
      state.currentTask = task;
      state.expandedSteps = {};
      renderTaskDetail(task);
      setFormStatus('任务 ' + (str(task.taskId) || '(无 ID)') + ' · ' + statusText(task.status), false);
      notify('任务状态：' + statusText(task.status), task.status === 'DONE' ? 'success' : 'warn');
      return refreshTasks().then(function () {
        selectTaskOption(str(task.taskId));
        if (state.auditTaskId === str(task.taskId)) return loadAudit(state.auditTaskId, { silent: true });
      });
    }).catch(function (err) {
      setFormStatus('提交失败', true);
      notify('提交任务失败：' + err.message, 'error');
    }).then(function () {
      setSubmitBusy(false);
    });
  }

  function selectTaskOption(taskId) {
    var sel = $('#task-select');
    if (sel && taskId) sel.value = taskId;
    var auditSel = $('#audit-task-select');
    if (auditSel && taskId && state.auditTaskId === taskId) auditSel.value = taskId;
  }

  function loadTask(taskId) {
    if (!taskId) return Promise.resolve();
    replace('#task-detail', loadingState('正在读取任务 ' + taskId));
    return getJson(API.task(taskId)).then(function (r) {
      var task = obj(r.data);
      state.currentTask = task;
      state.expandedSteps = {};
      renderTaskDetail(task);
      setFormStatus('已载入 ' + taskId + ' · ' + statusText(task.status), false);
    }).catch(function (err) {
      showRegionError('#task-detail', '读取任务 ' + taskId + ' 失败：' + err.message, 'task');
      notify('读取任务失败：' + err.message, 'error');
    });
  }

  /* ---- 任务详情：头部 + 派单流水线 ---- */

  function renderTaskDetail(task) {
    var host = $('#task-detail');
    if (!host) return;
    clear(host);

    if (!task || (!str(task.taskId) && !str(task.goal))) {
      host.appendChild(emptyState([
        '尚未载入任务。',
        h('br'),
        '在上方输入目标后点击「提交任务」，或从「最近任务」中选一条历史记录。'
      ]));
      return;
    }

    try {
      host.appendChild(renderTaskHeader(task));
      if(task.status==='NEEDS_INPUT') host.appendChild(renderClarification(task));
      host.appendChild(renderPipeline(task));
      var report = str(task.report);
      if (report) host.appendChild(renderReport(report));
    } catch (e) {
      showRegionError('#task-detail', '渲染任务视图时出错：' + (e && e.message ? e.message : e), 'task');
    }
  }

  /** 交付汇总报告（WorkflowEngine 的 report 阶段产物） */
  function renderReport(report) {
    return h('section', { class: 'card' }, [
      sectionTitle('交付汇总报告'),
      h('p', { class: 'hint', text: '由交付经理角色汇总各部门产出后生成，是这条任务对外交付物的一部分。' }),
      h('pre', { class: 'step-output task-report', text: report })
    ]);
  }

  function renderClarification(task) {
    if(!currentUser || currentUser.username!==task.requestedBy) return h('section',{class:'card'},[
      sectionTitle('等待申请人补充信息'),h('p',{text:task.inputQuestion||'请由任务申请人补充必要信息。'})]);
    var answer=h('textarea',{class:'goal-input',rows:'2',placeholder:'补充订单号、凭证或其他必要信息'});
    var button=h('button',{class:'btn btn-primary',text:'补充并继续',onclick:function(){
      if(!answer.value.trim()) return;
      button.disabled=true;
      postJson(API.task(task.taskId)+'/clarify',{answer:answer.value,revision:task.revision}).then(function(r){
        state.currentTask=r.data;renderTaskDetail(r.data);refreshTasks();
      }).catch(function(e){notify(e.message,'error');button.disabled=false;});
    }});
    return h('section',{class:'card'},[sectionTitle('需要补充信息'),h('p',{text:task.inputQuestion||'请补充必要信息。'}),answer,button]);
  }

  function renderTaskHeader(task) {
    var m = obj(task.metrics);
    var slaTag;
    if (task.status !== 'DONE') slaTag = badge('SLA · 待收口', 'tone-muted');
    else if (task.slaMet === true) slaTag = badge('SLA 达标', 'tone-ok');
    else if (task.slaMet === false) slaTag = badge('SLA 未达标', 'tone-danger');
    else slaTag = badge('SLA 未知', 'tone-muted');

    var denials = num(m.toolDenials, 0);
    var taskFailure = str(task.failureReason);
    var pendingReason = str(task.pendingApprovalReason);

    return h('section', { class: 'card task-header' }, [
      h('div', { class: 'task-title-row' }, [
        h('h2', { class: 'task-goal', text: str(task.goal) || '（无目标描述）' }),
        h('div', { class: 'badge-row' }, [
          badge('复杂度 · ' + (COMPLEXITY_LABEL[task.complexity] || str(task.complexity) || '未知'),
            'tone-' + (COMPLEXITY_TONE[task.complexity] || 'muted')),
          badge('状态 · ' + statusText(task.status), 'tone-' + (STATUS_TONE[task.status] || 'muted')),
          slaTag,
          str(task.approvalMode)
            ? badge(APPROVAL_MODE_LABEL[task.approvalMode] || ('模式 · ' + str(task.approvalMode)), 'tone-muted')
            : null,
          str(task.backend) ? badge('LLM · ' + str(task.backend), 'tone-muted') : null,
          str(task.embedding) ? badge('embedding · ' + str(task.embedding), 'tone-muted') : null,
          str(task.approvedBy) ? badge('审批人 · ' + str(task.approvedBy), 'tone-ok') : null
        ])
      ]),
      str(task.planRationale)
        ? h('p', { class: 'plan-rationale' }, [
            h('span', { class: 'plan-label', text: '规划依据' }),
            str(task.planRationale)
          ])
        : null,
      task.status === 'RUNNING' ? h('button', {class:'btn btn-primary', text:'恢复未完成任务', dataset:{resumeTask:task.taskId}}) : null,
      pendingReason ? h('p', { class: 'banner banner-warn' }, [h('span', { class: 'banner-label', text: '待审批原因' }), pendingReason]) : null,
      taskFailure ? h('p', { class: 'banner banner-danger' }, [h('span', { class: 'banner-label', text: '失败原因' }), taskFailure]) : null,
      h('div', { class: 'metric-row' }, [
        metric('总耗时', fmtMs(m.totalLatencyMs)),
        metric('Tokens', fmtInt(m.totalTokens)),
        metric('成本', fmtCost(m.totalCostUsd)),
        metric('工具调用', fmtInt(m.toolCalls)),
        metric('权限拒绝', fmtInt(m.toolDenials), denials > 0 ? 'is-bad' : ''),
        metric('重试', fmtInt(m.retries)),
        metric('步骤完成', fmtInt(m.stepsDone) + ' / ' + fmtInt(m.steps)),
        metric('任务号', str(task.taskId) || DASH, 'is-mono')
      ])
    ]);
  }

  function renderPipeline(task) {
    var steps = arr(task.steps);
    var pipeline = h('div', { class: 'pipeline' });

    if (!steps.length) {
      pipeline.appendChild(emptyState('该任务没有产生任何步骤（steps 为空）。'));
    } else {
      steps.forEach(function (step, index) {
        if (index > 0) pipeline.appendChild(h('div', { class: 'pipeline-arrow', 'aria-hidden': 'true', text: '→' }));
        pipeline.appendChild(renderStepCard(step, index));
      });
    }

    return h('section', { class: 'card' }, [
      sectionTitle('派单流水线 · 组织架构视图', null),
      h('p', { class: 'hint', text: '规划器把目标拆成步骤，每一步派给一个部门角色；卡片左侧色条表示步骤状态。' }),
      pipeline
    ]);
  }

  function renderStepCard(step, index) {
    var data = obj(step);
    var stepId = str(data.stepId) || ('step-' + (index + 1));
    var status = str(data.status) || 'CREATED';
    var toolCalls = arr(data.toolCalls);
    var denials = toolCalls.filter(function (c) { return obj(c).allowed === false; }).length;
    var citations = arr(data.citations);
    var approvalRequired = data.approvalRequired === true;
    var waitingApproval = status === 'WAITING_APPROVAL';
    var expanded = !!state.expandedSteps[stepId];

    var card = h('article', { class: 'step-card', dataset: { status: status, stepId: stepId } });

    /* 头部：步骤序号 + 状态徽章 */
    card.appendChild(h('header', { class: 'step-head' }, [
      h('span', { class: 'step-index', text: '步骤 ' + (index + 1) }),
      badge(statusText(status), 'tone-' + (STATUS_TONE[status] || 'muted'))
    ]));

    /* 组织架构：部门 → 角色 */
    card.appendChild(h('div', { class: 'step-route' }, [
      h('span', { class: 'dept', text: str(data.department) || '未知部门' }),
      h('span', { class: 'route-arrow', 'aria-hidden': 'true', text: '→' }),
      h('span', { class: 'role-name', text: str(data.roleName) || str(data.roleId) || '未知角色' })
    ]));
    if (str(data.roleId)) card.appendChild(h('div', { class: 'step-role-id', text: str(data.roleId) }));

    if (waitingApproval && state.currentTask) {
      var pending = arr(state.currentTask.pendingActions).filter(function(a) { return a.stepId === stepId; });
      card.appendChild(h('pre', {class:'banner', style:'white-space:pre-wrap;overflow-wrap:anywhere', text:'待审批操作（版本 '+state.currentTask.revision+'）\n'+JSON.stringify(pending,null,2)}));
      if (!canReview()) card.appendChild(h('p',{text:'请使用 reviewer 或 admin 账号核对并审批；申请人不能审批自己的任务。'}));
    }
    if (str(data.objective)) card.appendChild(h('p', { class: 'step-objective', text: str(data.objective) }));

    card.appendChild(h('div', { class: 'step-meta' }, [
      h('span', { text: '耗时 ' + fmtMs(data.latencyMs) }),
      h('span', { text: 'Tokens ' + fmtInt(data.tokens) }),
      h('span', { text: '重试 ' + fmtInt(data.retriesUsed) }),
      h('span', { text: '授权工具 ' + arr(data.allowedTools).length + ' 个' })
    ]));

    if (denials > 0) {
      card.appendChild(h('span', { class: 'deny-flag', text: '⚠ ' + denials + ' 次工具调用被 RoleGuard 拒绝' }));
    }
    if (str(data.failureReason)) {
      card.appendChild(h('p', { class: 'banner banner-danger' }, [
        h('span', { class: 'banner-label', text: '失败原因' }),
        str(data.failureReason)
      ]));
    }

    /* 审批闸门：仅 WAITING_APPROVAL 时提供动作 */
    if (waitingApproval && canReview()) {
      card.appendChild(h('div', { class: 'approval-gate' }, [
        h('span', { class: 'gate-text', text: '⚠ 高风险操作待审批放行' }),
        h('button', {
          type: 'button', class: 'btn btn-ok btn-sm', text: '批准',
          dataset: { approve: 'grant', stepId: stepId }
        }),
        h('button', {
          type: 'button', class: 'btn btn-danger btn-sm', text: '驳回',
          dataset: { approve: 'reject', stepId: stepId }
        })
      ]));
    } else if (approvalRequired && !waitingApproval) {
      card.appendChild(h('div', { class: 'gate-done', text: data.approved === true ? '✓ 已通过审批放行' : '✕ 审批未通过' }));
    }

    /* 展开 / 收起 */
    card.appendChild(h('button', {
      type: 'button', class: 'step-toggle',
      'aria-expanded': expanded ? 'true' : 'false',
      dataset: { stepToggle: stepId },
      text: expanded ? '收起详情 ▴' : '展开详情 ▾'
    }));

    var body = h('div', { class: 'step-body' });
    if (!expanded) body.setAttribute('hidden', '');

    /* 输出 */
    body.appendChild(h('div', null, [
      sectionTitle('输出'),
      str(data.output)
        ? h('pre', { class: 'step-output', text: str(data.output) })
        : h('p', { class: 'hint', text: '该步骤暂无输出文本。' })
    ]));

    /* 引用 */
    if(arr(data.trace).length) {
      var timeline=h('details',null,[h('summary',{text:'决策轨迹 · '+arr(data.trace).length+' 轮 · '+str(data.stopReason)})]);
      arr(data.trace).forEach(function(turn){
        timeline.appendChild(h('h4',{text:'第 '+turn.turn+' 轮 · '+turn.provider}));
        timeline.appendChild(h('pre',{class:'step-output',text:JSON.stringify({answer:turn.answer,toolRequests:turn.toolRequests,observations:turn.observations,inputTokens:turn.inputTokens,outputTokens:turn.outputTokens,usageEstimated:turn.usageEstimated},null,2)}));
      });
      body.appendChild(timeline);
    }
    var citeBox = h('div', null, [sectionTitle('检索证据 (' + citations.length + ') · 不代表结论已获支持')]);
    if (!citations.length) {
      citeBox.appendChild(h('p', { class: 'hint', text: '无引用（citations 为空）。' }));
    } else {
      var citeList = h('ul', { class: 'citation-list' });
      citations.forEach(function (raw) {
        var c = obj(raw);
        citeList.appendChild(h('li', { class: 'citation' }, [
          h('span', { class: 'cite-title', text: str(c.title) || str(c.docId) || '(无标题)' }),
          h('span', { class: 'cite-chunk', text: '[' + fmtInt(c.chunkIndex) + ']' }),
          h('span', { class: 'cite-score', text: 'score ' + fmtScore(c.score) }),
          h('span', { class: 'cite-doc', text: (str(c.namespace) ? str(c.namespace) + ' / ' : '') + str(c.docId) })
        ]));
      });
      citeBox.appendChild(citeList);
    }
    body.appendChild(citeBox);

    /* 工具调用：allowed=false 必须显著标红并展示 denyReason */
    var callBox = h('div', null, [sectionTitle('工具调用 (' + toolCalls.length + ')')]);
    if (!toolCalls.length) {
      callBox.appendChild(h('p', { class: 'hint', text: '无工具调用记录。' }));
    } else {
      var callList = h('ul', { class: 'tool-call-list' });
      toolCalls.forEach(function (raw) {
        var call = obj(raw);
        var allowed = call.allowed === true;
        var row = h('li', { class: 'tool-call ' + (allowed ? 'is-allowed' : 'is-denied') });

        var flags = [];
        flags.push(allowed ? '已放行' : '已拒绝');
        if (call.executed === true) flags.push('已执行');
        else if (allowed) flags.push('未执行');
        if (call.approvalRequired === true) flags.push('高风险·需审批');
        if (str(call.origin)) flags.push(ORIGIN_LABEL[str(call.origin)] || str(call.origin));
        if (call.latencyMs !== undefined && call.latencyMs !== null) flags.push(fmtMs(call.latencyMs));

        row.appendChild(h('div', { class: 'tool-call-row' }, [
          h('span', { class: 'tool-verdict', text: allowed ? '✓ 允许' : '✕ 拒绝' }),
          h('span', { class: 'tool-name', text: str(call.toolName) || '(未命名工具)' }),
          h('span', { class: 'tool-flags', text: flags.join(' · ') }),
          call.repeated === true ? h('span', { class: 'tag tone-warn', text: '重复调用' }) : null
        ]));

        if (!allowed) {
          row.appendChild(h('div', {
            class: 'deny-reason',
            text: str(call.denyReason) || '拒绝原因未提供（denyReason 为空）'
          }));
        }
        if (str(call.arguments)) {
          row.appendChild(h('div', { class: 'tool-args', text: 'arguments: ' + str(call.arguments) }));
        }
        // 工具返回预览：真实数据长这样，超长时截断避免卡片被撑爆
        if (str(call.payload)) {
          var preview = str(call.payload);
          row.appendChild(h('div', { class: 'tool-payload-label', text: '返回预览' }));
          row.appendChild(h('pre', {
            class: 'tool-payload',
            text: preview.length > 600 ? preview.slice(0, 600) + ' …（已截断）' : preview
          }));
        }
        callList.appendChild(row);
      });
      callBox.appendChild(callList);
    }
    body.appendChild(callBox);

    /* 本步骤授权范围 */
    var allowedTools = arr(data.allowedTools);
    var scopeBox = h('div', null, [sectionTitle('本步骤授权工具')]);
    if (!allowedTools.length) {
      scopeBox.appendChild(h('p', { class: 'hint', text: '未声明授权工具。' }));
    } else {
      var chips = h('ul', { class: 'tool-chip-list' });
      allowedTools.forEach(function (t) { chips.appendChild(h('li', { class: 'tool-chip', text: str(t) })); });
      scopeBox.appendChild(chips);
    }
    body.appendChild(scopeBox);

    card.appendChild(body);
    return card;
  }

  function toggleStep(btn) {
    var card = btn.closest ? btn.closest('.step-card') : null;
    if (!card) return;
    var body = card.querySelector('.step-body');
    if (!body) return;
    var key = btn.dataset.stepToggle;
    var willOpen = body.hasAttribute('hidden');
    if (willOpen) {
      body.removeAttribute('hidden');
      state.expandedSteps[key] = true;
    } else {
      body.setAttribute('hidden', '');
      delete state.expandedSteps[key];
    }
    btn.setAttribute('aria-expanded', willOpen ? 'true' : 'false');
    btn.textContent = willOpen ? '收起详情 ▴' : '展开详情 ▾';
  }

  /* ---- 审批闸门：POST /api/tasks/{taskId}/approve ---- */

  function handleApproval(granted) {
    var task = state.currentTask;
    var taskId = task ? str(task.taskId) : '';
    if (!taskId) {
      notify('当前没有可审批的任务。', 'warn');
      return;
    }
    var buttons = document.querySelectorAll('[data-approve]');
    Array.prototype.forEach.call(buttons, function (b) { b.disabled = true; });

    postJson(API.approve(taskId), { granted: !!granted, revision: state.currentTask.revision, reason: granted ? "已核对操作参数" : "人工驳回" }).then(function (r) {
      var updated = obj(r.data);
      if (!str(updated.taskId)) {
        // 返回体不含 taskId 时回退到重新拉取
        return loadTask(taskId);
      }
      state.currentTask = updated;
      renderTaskDetail(updated);
      setFormStatus('任务 ' + taskId + ' · ' + statusText(updated.status), false);
      notify('审批已处理，当前任务状态：' + statusText(updated.status), updated.status === 'DONE' ? 'success' : 'warn');
      return refreshTasks().then(function () {
        if (state.auditTaskId === taskId) return loadAudit(taskId, { silent: true });
      });
    }).catch(function (err) {
      notify('审批操作失败：' + err.message, 'error');
      Array.prototype.forEach.call(document.querySelectorAll('[data-approve]'), function (b) { b.disabled = false; });
    });
  }

  /* ======================================================================
   * 7. ② 权限矩阵
   * ==================================================================== */

  function permissionSetOf(role) {
    var set = {};
    arr(obj(role).permissions).forEach(function (p) {
      if (isObj(p) && p.code) set[str(p.code)] = true;
      else set[str(p)] = true;
    });
    return set;
  }

  function loadOrg() {
    state.orgLoaded = false;
    replace('#matrix-container', loadingState('正在加载组织架构'));
    replace('#tool-catalog-container', loadingState('正在加载工具目录'));
    return Promise.all([
      getJson(API.roles).then(function (r) { state.roles = arr(r.data).filter(isObj); }, function (e) { throw e; }),
      getJson(API.tools).then(function (r) { state.tools = arr(r.data).filter(isObj); }, function (e) { throw e; })
    ]).then(function () {
      state.orgLoaded = true;
      renderMatrix();
      renderToolCatalog();
      return loadRoleMetrics({ silent: true });
    }).catch(function (err) {
      showRegionError('#matrix-container', '加载角色/工具目录失败：' + err.message, 'org');
      showRegionError('#tool-catalog-container', '加载工具目录失败：' + err.message, 'org');
      notify('加载组织架构失败：' + err.message, 'error');
    });
  }

  function renderMatrix() {
    var host = $('#matrix-container');
    if (!host) return;
    var roles = state.roles;
    var tools = state.tools;
    clear(host);

    if (!roles.length || !tools.length) {
      host.appendChild(emptyState('暂无角色或工具数据。请确认后端 /api/org/roles 与 /api/org/tools 可用。'));
      renderMatrixLegend(0, 0, 0);
      return;
    }

    var table = h('table', { class: 'matrix-table' });

    /* 表头：工具名（横向可滚动） */
    var headRow = h('tr', null, [h('th', { class: 'role-head', text: '角色 \\ 工具' })]);
    tools.forEach(function (tool) {
      var t = obj(tool);
      var kids = [h('span', { class: 'tool-head-name', text: str(t.name) })];
      if (str(t.requiredPermission)) {
        kids.push(h('span', { class: 'tool-head-perm', text: str(t.requiredPermission) }));
      }
      headRow.appendChild(h('th', {
        class: 'tool-head',
        title: str(t.description) + (str(t.risk) ? '（风险：' + (RISK_LABEL[t.risk] || str(t.risk)) + '）' : '')
      }, kids));
    });
    table.appendChild(h('thead', null, headRow));

    /* 表体：行 = 角色 */
    var body = h('tbody');
    var okCount = 0, warnCount = 0, total = 0;

    roles.forEach(function (role) {
      var r = obj(role);
      var perms = permissionSetOf(r);
      var row = h('tr', null, [
        h('td', { class: 'role-head', title: roleTooltip(r) }, [
          h('span', { class: 'role-head-name', text: str(r.name) || str(r.id) }),
          h('span', { class: 'role-head-meta', text: (str(r.department) ? str(r.department) + ' · ' : '') + str(r.id) }),
          h('span', { class: 'role-head-meta', text: '权限 ' + arr(r.permissions).length + ' 项 · 知识域 ' + arr(r.knowledgeNamespaces).length + ' 个' }),
          h('span', { class: 'role-head-meta', text: '交付 ' + fmtList(r.deliverableKeys) + ' · 重试上限 ' + fmtInt(r.maxRetries) })
        ])
      ]);

      tools.forEach(function (tool) {
        var t = obj(tool);
        var perm = str(t.requiredPermission);
        var allowed = !!perms[perm];
        var highRisk = str(t.risk) === 'HIGH';
        total++;
        var cell;
        if (allowed && highRisk) {
          warnCount++;
          cell = h('td', { class: 'cell-warn', title: '可调用，但属高风险工具，需审批放行：' + str(t.name) });
          cell.textContent = '⚠';
        } else if (allowed) {
          okCount++;
          cell = h('td', { class: 'cell-ok', title: '可调用：' + str(r.id) + ' 持有 ' + perm });
          cell.textContent = '✓';
        } else {
          cell = h('td', { class: 'cell-no', title: '不可调用：缺少权限 ' + (perm || '(工具未声明权限)') });
          cell.textContent = '—';
        }
        row.appendChild(cell);
      });
      body.appendChild(row);
    });

    table.appendChild(body);
    host.appendChild(table);
    renderMatrixLegend(roles.length, tools.length, total, okCount, warnCount);
  }

  /** 角色档案 tooltip：mission / 权限 / 知识域 / 交付物，与 Role 契约一一对应 */
  function roleTooltip(role) {
    var r = obj(role);
    return [
      '岗位使命：' + (str(r.mission) || '（未声明）'),
      '权限集合：' + fmtList(r.permissions),
      '可读知识命名空间：' + fmtList(r.knowledgeNamespaces),
      '必须交付字段：' + fmtList(r.deliverableKeys),
      '最大重试次数：' + fmtInt(r.maxRetries)
    ].join('\n');
  }

  function renderMatrixLegend(roleCount, toolCount, total, okCount, warnCount) {
    var host = $('#matrix-legend');
    if (!host) return;
    clear(host);
    if (!total) {
      host.appendChild(h('span', { text: '矩阵为空：未取到角色或工具数据。' }));
      return;
    }
    host.appendChild(h('span', null, [
      '✓ 可调用 ' + String(okCount) + ' 格'
    ]));
    host.appendChild(h('span', null, [
      '⚠ 高风险可调用 ' + String(warnCount) + ' 格'
    ]));
    host.appendChild(h('span', null, [
      '— 无权限 ' + String(total - okCount - warnCount) + ' 格'
    ]));
    host.appendChild(h('span', { text: roleCount + ' 个角色 × ' + toolCount + ' 个工具 = ' + total + ' 个权限判定' }));
  }

  /**
   * 工具目录：把 ToolCatalog 的 MCP 工具契约摊开展示
   * （name + description + 参数 schema + 所需权限 + 风险 + 幂等）。
   */
  function renderToolCatalog() {
    var host = replace('#tool-catalog-container', null);
    if (!host) return;
    var tools = state.tools;
    if (!tools.length) {
      host.appendChild(emptyState('暂无工具数据。请确认后端 /api/org/tools 可用。'));
      return;
    }

    var headRow = h('tr', null, ['工具 name', '说明 description', '参数 parameters', '必填参数', '所需权限 requiredPermission', '风险 risk', '幂等 idempotent']
      .map(function (label) { return h('th', { text: label }); }));

    var body = h('tbody');
    tools.forEach(function (raw) {
      var t = obj(raw);
      var params = obj(t.parameters);
      var required = arr(t.requiredParameters).map(str);
      var paramKeys = Object.keys(params);
      var risk = str(t.risk);
      var riskTone = risk === 'HIGH' ? 'tone-danger' : (risk === 'MEDIUM' ? 'tone-warn' : 'tone-ok');

      /* 参数：必填项前加 * */
      var paramText = paramKeys.length
        ? paramKeys.map(function (k) {
            return (required.indexOf(k) >= 0 ? '*' : '') + k + ': ' + str(params[k]);
          }).join(', ')
        : DASH;

      var riskCell = h('td', null, [badge(risk || '未知', riskTone)]);
      if (risk === 'HIGH') riskCell.appendChild(h('span', { class: 'risk-note', text: '需审批放行' }));

      body.appendChild(h('tr', null, [
        h('td', { class: 'mono', text: str(t.name) || DASH }),
        h('td', { class: 'cell-wrap', text: str(t.description) || DASH }),
        h('td', { class: 'mono cell-wrap', text: paramText }),
        h('td', { class: 'mono', text: required.length ? required.join(', ') : DASH }),
        h('td', { class: 'mono', text: str(t.requiredPermission) || DASH }),
        riskCell,
        h('td', { class: 'cell-num', text: t.idempotent === true ? '✓ 是' : (t.idempotent === false ? '✕ 否' : DASH) })
      ]));
    });

    host.appendChild(h('table', null, [h('thead', null, headRow), body]));
  }

  function loadRoleMetrics(opts) {
    if (!canReview()) {
      var host = document.querySelector('#role-metrics-container');
      if (host) host.textContent = '部门统计仅审核员和管理员可见。';
      return Promise.resolve();
    }
    var silent = !!(opts && opts.silent);
    if (!silent) replace('#role-metrics-container', loadingState('正在加载角色指标'));
    return getJson(API.roleMetrics).then(function (r) {
      state.roleMetrics = arr(r.data).filter(isObj);
      renderRoleMetrics();
    }).catch(function (err) {
      if (!silent) {
        showRegionError('#role-metrics-container', '加载角色维度聚合失败：' + err.message, 'roleMetrics');
      } else {
        replace('#role-metrics-container', h('p', { class: 'hint', text: '角色维度聚合暂不可用：' + err.message }));
      }
      notify('加载角色指标失败：' + err.message, 'error');
    });
  }

  function renderRoleMetrics() {
    var host = $('#role-metrics-container');
    if (!host) return;
    clear(host);
    var rows = state.roleMetrics;
    if (!rows.length) {
      host.appendChild(emptyState('暂无角色维度指标（可能还没有跑过任务）。'));
      return;
    }
    var nameOf = {};
    state.roles.forEach(function (r) { nameOf[str(obj(r).id)] = str(obj(r).name); });

    var headRow = h('tr', null, ['角色', '任务数', '步骤数', 'Tokens', '成本', '平均延迟', '工具调用', '权限拒绝', '重试', 'SLA 达标率']
      .map(function (label, i) { return h('th', { class: i === 0 ? '' : 'cell-num', text: label }); }));

    var body = h('tbody');
    rows.forEach(function (raw) {
      var m = obj(raw);
      var roleId = str(m.roleId);
      // RoleMetricsView 自带 roleName / department；缺失时回退到 /api/org/roles 的映射
      var displayName = str(m.roleName) || nameOf[roleId] || roleId;
      var dept = str(m.department);
      var slaRate = Number(m.slaMetRate);
      var slaClass = !isFinite(slaRate) ? 'cell-num' : (slaRate >= 0.9 ? 'cell-num tone-ok' : (slaRate >= 0.7 ? 'cell-num tone-warn' : 'cell-num tone-danger'));
      body.appendChild(h('tr', null, [
        h('td', null, [
          h('span', { class: 'role-head-name', text: displayName }),
          h('span', { class: 'role-head-meta', text: (dept && dept !== '-' ? dept + ' · ' : '') + roleId })
        ]),
        h('td', { class: 'cell-num', text: fmtInt(m.tasks) }),
        h('td', { class: 'cell-num', text: fmtInt(m.steps) }),
        h('td', { class: 'cell-num', text: fmtInt(m.tokens) }),
        h('td', { class: 'cell-num', text: fmtCost(m.costUsd) }),
        h('td', { class: 'cell-num', text: fmtMs(m.avgLatencyMs) }),
        h('td', { class: 'cell-num', text: fmtInt(m.toolCalls) }),
        h('td', { class: 'cell-num' + (num(m.toolDenials, 0) > 0 ? ' tone-danger' : ''), text: fmtInt(m.toolDenials) }),
        h('td', { class: 'cell-num', text: fmtInt(m.retries) }),
        h('td', { class: slaClass, text: fmtRate(m.slaMetRate) })
      ]));
    });
    host.appendChild(h('table', null, [h('thead', null, headRow), body]));
  }

  /* ======================================================================
   * 8. ③ 审计日志
   * ==================================================================== */

  function loadAudit(taskId, opts) {
    var silent = !!(opts && opts.silent);
    if (!taskId) {
      state.auditTaskId = '';
      state.auditLoadedFor = '';
      state.auditEvents = [];
      renderAudit();
      return Promise.resolve();
    }
    state.auditTaskId = taskId;
    if (!silent) replace('#audit-list', loadingState('正在加载审计轨迹'));
    return getJson(API.audit(taskId)).then(function (r) {
      state.auditEvents = normalizeAudit(arr(r.data));
      state.auditLoadedFor = taskId;
      renderAudit();
    }).catch(function (err) {
      state.auditEvents = [];
      state.auditLoadedFor = '';
      showRegionError('#audit-list', '加载任务 ' + taskId + ' 的审计日志失败：' + err.message, 'audit');
      var meta = replace('#audit-meta', null);
      if (meta) meta.appendChild(h('span', { class: 'hint', text: '任务 ' + taskId }));
      notify('加载审计日志失败：' + err.message, 'error');
    });
  }

  /** 归一化并按时间倒序排序；无时间戳时按原始顺序倒序兜底 */
  function normalizeAudit(list) {
    var items = list.map(function (raw, index) {
      var ev = obj(raw);
      var t = Date.parse(str(ev.at));
      return {
        raw: ev,
        index: index,
        time: isNaN(t) ? null : t,
        key: [str(ev.taskId), str(ev.stepId), str(ev.type), str(ev.at), index].join('|')
      };
    });
    items.sort(function (a, b) {
      if (a.time === null && b.time === null) return b.index - a.index;
      if (a.time === null) return 1;
      if (b.time === null) return -1;
      if (b.time !== a.time) return b.time - a.time;
      return b.index - a.index;
    });
    return items;
  }

  function renderAudit() {
    var host = replace('#audit-list', null);
    var meta = replace('#audit-meta', null);
    if (!host || !meta) return;

    if (!state.auditTaskId) {
      host.appendChild(emptyState([
        '请选择一个任务查看其审计轨迹。',
        h('br'),
        '审计事件由 RoleGuard 与 WorkflowEngine 在每次鉴权、派单、审批时写入。'
      ]));
      return;
    }

    var events = state.auditEvents;
    meta.appendChild(h('span', { class: 'hint', text: '任务 ' + state.auditTaskId + ' · 共 ' + events.length + ' 条事件（时间倒序）' }));

    /* 导出：合规团队需要把审计轨迹交给别人（评估方、监管、事故复盘），
       导出动作本身也会被记一条 AUDIT_EXPORTED。 */
    ['csv', 'json', 'md'].forEach(function (fmt) {
      var a = h('a', {
        class: 'btn btn-ghost btn-sm',
        href: API.auditExport(state.auditTaskId, fmt),
        download: '',
        text: '导出 ' + fmt.toUpperCase()
      });
      meta.appendChild(a);
    });

    if (!events.length) {
      host.appendChild(emptyState('该任务暂无审计事件。'));
      return;
    }

    /* 事件类型计数：一眼看出有没有拒绝与审批 */
    var counts = {};
    events.forEach(function (it) {
      var t = str(it.raw.type) || 'UNKNOWN';
      counts[t] = (counts[t] || 0) + 1;
    });
    Object.keys(counts).sort(function (a, b) { return counts[b] - counts[a]; })
      .forEach(function (t) {
        meta.appendChild(badge(t + ' × ' + counts[t], 'tone-' + (AUDIT_TONE[t] || 'muted')));
      });

    var list = h('ul', { class: 'audit-list' });
    events.forEach(function (it) {
      var ev = it.raw;
      var type = str(ev.type) || 'UNKNOWN';
      var open = !!state.expandedAudit[it.key];

      var item = h('li', { class: 'audit-item' + (open ? ' is-open' : '') });
      item.appendChild(h('button', {
        type: 'button', class: 'audit-head',
        'aria-expanded': open ? 'true' : 'false',
        dataset: { auditToggle: it.key },
        title: (AUDIT_LABEL[type] || type) + ' · ' + str(ev.at)
      }, [
        h('span', { class: 'audit-time', text: fmtClock(ev.at) }),
        badge(type, 'tone-' + (AUDIT_TONE[type] || 'muted')),
        h('span', { class: 'audit-actor', text: (str(ev.roleId) || 'system') + (str(ev.stepId) ? ' · ' + str(ev.stepId) : '') }),
        h('span', { class: 'audit-summary', text: str(ev.summary) || (AUDIT_LABEL[type] || '(无摘要)') }),
        h('span', { class: 'audit-caret', text: open ? '▴' : '▾' })
      ]));

      var body = h('div', { class: 'audit-body' });
      if (!open) body.setAttribute('hidden', '');

      var attrs = obj(ev.attributes);
      var attrKeys = Object.keys(attrs);
      body.appendChild(sectionTitle('事件上下文'));
      var kv = h('dl', { class: 'kv' });
      kv.appendChild(h('dt', { text: 'at' }));
      kv.appendChild(h('dd', { text: fmtDateTime(ev.at) + '  (' + (str(ev.at) || '无时间戳') + ')' }));
      kv.appendChild(h('dt', { text: 'taskId' }));
      kv.appendChild(h('dd', { text: str(ev.taskId) || DASH }));
      kv.appendChild(h('dt', { text: 'stepId' }));
      kv.appendChild(h('dd', { text: str(ev.stepId) || DASH }));
      kv.appendChild(h('dt', { text: 'roleId' }));
      kv.appendChild(h('dd', { text: str(ev.roleId) || DASH }));
      kv.appendChild(h('dt', { text: 'type' }));
      kv.appendChild(h('dd', { text: type + (AUDIT_LABEL[type] ? '（' + AUDIT_LABEL[type] + '）' : '') }));
      kv.appendChild(h('dt', { text: 'summary' }));
      kv.appendChild(h('dd', { text: str(ev.summary) || DASH }));
      body.appendChild(kv);

      body.appendChild(sectionTitle('attributes (' + attrKeys.length + ')'));
      if (!attrKeys.length) {
        body.appendChild(h('p', { class: 'hint', text: '该事件没有附加属性。' }));
      } else {
        var akv = h('dl', { class: 'kv' });
        attrKeys.forEach(function (k) {
          var v = attrs[k];
          akv.appendChild(h('dt', { text: k }));
          akv.appendChild(h('dd', { text: isObj(v) || Array.isArray(v) ? JSON.stringify(v) : str(v) }));
        });
        body.appendChild(akv);
      }

      item.appendChild(body);
      list.appendChild(item);
    });

    host.appendChild(list);
  }

  function toggleAudit(btn) {
    var item = btn.closest ? btn.closest('.audit-item') : null;
    if (!item) return;
    var body = item.querySelector('.audit-body');
    if (!body) return;
    var key = btn.dataset.auditToggle;
    var willOpen = body.hasAttribute('hidden');
    if (willOpen) {
      body.removeAttribute('hidden');
      state.expandedAudit[key] = true;
      item.classList.add('is-open');
    } else {
      body.setAttribute('hidden', '');
      delete state.expandedAudit[key];
      item.classList.remove('is-open');
    }
    btn.setAttribute('aria-expanded', willOpen ? 'true' : 'false');
    var caret = btn.querySelector('.audit-caret');
    if (caret) caret.textContent = willOpen ? '▴' : '▾';
  }

  /* ======================================================================
   * 9. ④ 评测报告
   * ==================================================================== */

  function loadEval() {
    replace('#eval-body', loadingState('正在加载评测报告'));
    return getJson(API.evalLatest, { tolerate404: true }).then(function (r) {
      if (r.missing || !isObj(r.data)) {
        state.evalReport = null;
        state.evalMissing = true;
        state.evalMissingBody = r.data;   // 后端 404 体里的 {error, hint}
      } else {
        state.evalReport = obj(r.data);
        state.evalMissing = false;
        state.evalMissingBody = null;
      }
      state.evalLoaded = true;
      renderEval();
    }).catch(function (err) {
      state.evalLoaded = false;
      showRegionError('#eval-body', '加载评测报告失败：' + err.message, 'eval');
      notify('加载评测报告失败：' + err.message, 'error');
    });
  }

  function renderEval() {
    var host = replace('#eval-body', null);
    if (!host) return;

    if (state.evalMissing) {
      var hint = errorMessageOf(state.evalMissingBody) || str(obj(state.evalMissingBody).hint);
      host.appendChild(emptyState([
        '尚未生成评测报告。',
        h('br'),
        '运行 ',
        h('code', { text: 'mvn -Peval' }),
        ' 或 ',
        h('code', { text: 'bash scripts/run-eval.sh' }),
        ' 后刷新本页。',
        hint ? h('div', { class: 'eval-hint', text: '后端提示：' + hint }) : null
      ]));
      return;
    }

    var report = obj(state.evalReport);
    // EvalReport 的指标是平铺在顶层的（routingAccuracy / toolCompliance ...）；
    // 若后端改成嵌套在 metrics 下，这里自动兼容两种形状。
    var m = isObj(report.metrics) ? report.metrics : report;
    var passRate = Number(report.passRate);
    var passRateClass = !isFinite(passRate) ? '' : (passRate >= 0.9 ? ' is-good' : (passRate >= 0.7 ? ' is-warn' : ' is-bad'));

    /* 报告元信息 */
    host.appendChild(h('div', { class: 'eval-meta' }, [
      badge('生成时间 ' + fmtDateTime(report.generatedAt), 'tone-muted'),
      badge('embedding · ' + (str(report.embedding) || '未知'), 'tone-muted'),
      badge('llm · ' + (str(report.llm) || '未知'), 'tone-muted'),
      badge('用例 ' + fmtInt(report.passed) + ' / ' + fmtInt(report.total), 'tone-info')
    ]));

    /* 指标卡 */
    var violations = Number(m.permissionViolations);
    var cards = h('div', { class: 'eval-cards' }, [
      evalCard('通过率 passRate', fmtRate(report.passRate), 'is-primary' + passRateClass),
      evalCard('路由准确率 routingAccuracy', fmtRate(m.routingAccuracy)),
      evalCard('预期工具请求覆盖（含平台取证）', fmtRate(m.toolCompliance)),
      evalCard('检索命中率', fmtRate(m.citationHitRate)),
      evalCard('回答引用命中（非语义支持）', fmtRate(m.answerCitationHitRate)),
      evalCard('任务完成率', fmtRate(m.taskCompletionRate)),
      evalCard('权限越权 permissionViolations', fmtInt(m.permissionViolations),
        isFinite(violations) && violations > 0 ? 'is-bad' : 'is-good'),
      evalCard('平均延迟 avgLatencyMs', fmtMs(m.avgLatencyMs)),
      evalCard('平均 Tokens avgTokens', fmtInt(m.avgTokens)),
      evalCard('总成本 totalCostUsd', fmtCost(m.totalCostUsd))
    ]);
    host.appendChild(cards);

    /* 用例表格 */
    var cases = arr(report.cases).filter(isObj);
    host.appendChild(sectionTitle('用例明细 (' + cases.length + ')'));
    if (!cases.length) {
      host.appendChild(emptyState('报告中没有用例明细（cases 为空）。'));
      return;
    }

    var headRow = h('tr', null, [
      h('th', { text: '用例' }),
      h('th', { text: '目标 goal' }),
      h('th', { text: '结果' }),
      h('th', { text: '路由 期望 / 实际' }),
      h('th', { text: '工具 期望 / 实际 / 已执行 / 被拒' }),
      h('th', { text: '引用命中' }),
      h('th', { text: '权限越权' }),
      h('th', { text: '终止状态' }),
      h('th', { class: 'cell-num', text: '耗时' }),
      h('th', { class: 'cell-num', text: 'Tokens' }),
      h('th', { text: '备注 notes' })
    ]);

    var body = h('tbody');
    cases.forEach(function (raw) {
      var c = obj(raw);
      var passed = c.passed === true;
      // 兼容两种命名：EvalResult 用 expectRoles / expectTools
      var expectedRoles = c.expectRoles !== undefined ? c.expectRoles : c.expectedRoles;
      var expectedTools = c.expectTools !== undefined ? c.expectTools : c.expectedTools;
      // 备注：优先 notes，退化为 failures 列表
      var notes = str(c.notes) || fmtList(c.failures);
      body.appendChild(h('tr', null, [
        h('td', { class: 'mono', text: str(c.caseId) || DASH }),
        h('td', { class: 'cell-wrap', text: str(c.goal) || DASH }),
        h('td', null, [h('span', { class: 'pass-pill ' + (passed ? 'is-pass' : 'is-fail'), text: passed ? '✓ 通过' : '✕ 失败' })]),
        h('td', null, [compareCell(expectedRoles, c.actualRoles)]),
        h('td', null, [compareCell(expectedTools, c.actualTools, c.deniedTools, c.executedTools)]),
        h('td', null, [h('span', { class: 'pass-pill ' + (c.citationHit === true ? 'is-pass' : 'is-fail'), text: c.citationHit === true ? '✓ 命中' : '✕ 未命中' })]),
        h('td', null, [h('span', {
          class: 'pass-pill ' + (c.permissionViolation === true ? 'is-fail' : 'is-pass'),
          text: c.permissionViolation === true ? '⚠ 有越权' : '✓ 无越权'
        })]),
        h('td', null, [badge(str(c.status) || DASH, 'tone-' + (STATUS_TONE[str(c.status)] || 'muted'))]),
        h('td', { class: 'cell-num', text: fmtMs(c.latencyMs) }),
        h('td', { class: 'cell-num', text: fmtInt(c.tokens) }),
        h('td', { class: 'cell-wrap', text: notes || DASH })
      ]));
    });

    host.appendChild(h('div', { class: 'table-scroll' }, [
      h('table', null, [h('thead', null, headRow), body])
    ]));
  }

  function evalCard(label, value, extraClass) {
    return h('div', { class: 'eval-card' + (extraClass ? ' ' + extraClass : '') }, [
      h('span', { class: 'metric-label', text: label }),
      h('span', { class: 'eval-value', text: value })
    ]);
  }

  /**
   * 期望 / 实际 对比：不一致时把实际值标红。
   * denied —— 被 RoleGuard 拒绝的工具；executed —— 真正执行成功的工具。
   * 「已执行」只在它少于「实际」时单独列出，正好点出「尝试了但被拦住」的用例。
   */
  function compareCell(expected, actual, denied, executed) {
    var e = arr(expected).map(str).filter(function (x) { return x; }).sort();
    var a = arr(actual).map(str).filter(function (x) { return x; }).sort();
    var d = arr(denied).map(str).filter(function (x) { return x; });
    var x = arr(executed).map(str).filter(function (x) { return x; });
    var same = e.length === a.length && e.every(function (x, i) { return x === a[i]; });
    var lines = [
      h('div', { class: 'cmp-line' }, [
        h('span', { class: 'cmp-key', text: '期望' }),
        h('span', { class: 'cmp-val', text: e.length ? e.join(', ') : DASH })
      ]),
      h('div', { class: 'cmp-line' }, [
        h('span', { class: 'cmp-key', text: '实际' }),
        h('span', { class: 'cmp-val', text: a.length ? a.join(', ') : DASH })
      ])
    ];
    if (x.length && (executed !== undefined) && x.length !== a.length) {
      lines.push(h('div', { class: 'cmp-line' }, [
        h('span', { class: 'cmp-key', text: '已执行' }),
        h('span', { class: 'cmp-val', text: x.join(', ') })
      ]));
    }
    if (d.length) {
      lines.push(h('div', { class: 'cmp-line' }, [
        h('span', { class: 'cmp-key', text: '被拒' }),
        h('span', { class: 'cmp-val is-denied', text: d.join(', ') })
      ]));
    }
    return h('div', { class: 'cmp ' + (same ? 'is-match' : 'is-mismatch') }, lines);
  }

  /* ======================================================================
   * 10. 任务列表 / Tab 切换 / 事件绑定
   * ==================================================================== */

  function refreshTasks() {
    return getJson(API.tasks).then(function (r) {
      state.tasks = arr(r.data).filter(isObj);
      renderTaskOptions();
    }).catch(function (err) {
      state.tasks = [];
      renderTaskOptions();
      notify('加载任务列表失败：' + err.message, 'error');
    });
  }

  function renderTaskOptions() {
    [['#task-select', true], ['#audit-task-select', false]].forEach(function (pair) {
      var el = $(pair[0]);
      if (!el) return;
      var isPrimary = pair[1];
      var previous = el.value;
      clear(el);

      if (!state.tasks.length) {
        el.appendChild(h('option', { value: '', text: '（暂无任务）' }));
        return;
      }
      el.appendChild(h('option', { value: '', text: '请选择任务…' }));
      state.tasks.forEach(function (raw) {
        var t = obj(raw);
        var id = str(t.taskId);
        if (!id) return;
        el.appendChild(h('option', {
          value: id,
          text: id + ' · ' + statusText(t.status) + ' · ' + truncate(t.goal, 40)
        }));
      });

      var current = str(state.currentTask && state.currentTask.taskId);
      if (previous && state.tasks.some(function (t) { return str(obj(t).taskId) === previous; })) el.value = previous;
      else if (isPrimary && current) el.value = current;
      else if (!isPrimary && state.auditTaskId) el.value = state.auditTaskId;
    });
  }

  function switchTab(name) {
    if (!name) return;
    state.activeTab = name;
    Array.prototype.forEach.call(document.querySelectorAll('[data-tab]'), function (btn) {
      var on = btn.dataset.tab === name;
      btn.classList.toggle('is-active', on);
      btn.setAttribute('aria-selected', on ? 'true' : 'false');
    });
    Array.prototype.forEach.call(document.querySelectorAll('[data-panel]'), function (panel) {
      panel.classList.toggle('is-active', panel.dataset.panel === name);
    });

    if (name === 'matrix' && !state.orgLoaded && !state.roles.length) loadOrg();
    if (name === 'audit') ensureAudit();
    if (name === 'eval' && !state.evalLoaded) loadEval();
  }

  /** 审计页：未选任务时默认选最新的一个 */
  function ensureAudit() {
    if (!state.auditTaskId) {
      var first = state.tasks.length ? obj(state.tasks[0]) : null;
      if (first && str(first.taskId)) {
        state.auditTaskId = str(first.taskId);
        renderTaskOptions();
      }
    }
    if (!state.auditTaskId) { renderAudit(); return; }
    if (state.auditLoadedFor !== state.auditTaskId) loadAudit(state.auditTaskId);
  }

  var RETRY_ACTIONS = {
    org: function () { loadOrg(); },
    roleMetrics: function () { loadRoleMetrics(); },
    tasks: function () { refreshTasks(); },
    task: function () {
      var id = str(state.currentTask && state.currentTask.taskId);
      if (id) loadTask(id); else refreshTasks();
    },
    audit: function () { loadAudit(state.auditTaskId); },
    eval: function () { loadEval(); }
  };

  /** 单一委托点击入口：避免重复绑定与重渲染后事件丢失 */
  function onDocumentClick(ev) {
    var target = ev.target;
    if (!target || !target.closest) return;

    var tabBtn = target.closest('[data-tab]');
    if (tabBtn) { switchTab(tabBtn.dataset.tab); return; }

    var sampleBtn = target.closest('[data-sample]');
    if (sampleBtn) { applySample(sampleBtn.dataset.sample); return; }

    var retryBtn = target.closest('[data-retry]');
    if (retryBtn) {
      var action = RETRY_ACTIONS[retryBtn.dataset.retry];
      if (action) action();
      return;
    }

    var resumeBtn = target.closest('[data-resume-task]');
    if (resumeBtn) { postJson('/api/tasks/'+encodeURIComponent(resumeBtn.dataset.resumeTask)+'/resume',{}).then(function(r){state.currentTask=r.data;renderTaskDetail(r.data);refreshTasks();}).catch(function(e){notify(e.message,'error');}); return; }
    var approveBtn = target.closest('[data-approve]');
    if (approveBtn) { handleApproval(approveBtn.dataset.approve === 'grant'); return; }

    var stepBtn = target.closest('[data-step-toggle]');
    if (stepBtn) { toggleStep(stepBtn); return; }

    var auditBtn = target.closest('[data-audit-toggle]');
    if (auditBtn) { toggleAudit(auditBtn); return; }
  }

  function bindStaticEvents() {
    document.addEventListener('click', onDocumentClick);

    var submit = $('#btn-submit');
    if (submit) submit.addEventListener('click', submitTask);

    var goal = $('#goal-input');
    if (goal) {
      goal.addEventListener('keydown', function (ev) {
        // Ctrl/Cmd + Enter 提交
        if ((ev.ctrlKey || ev.metaKey) && ev.key === 'Enter') { ev.preventDefault(); submitTask(); }
      });
    }

    var refreshTasksBtn = $('#btn-refresh-tasks');
    if (refreshTasksBtn) {
      refreshTasksBtn.addEventListener('click', function () {
        refreshTasks().then(function () { notify('任务列表已刷新', 'info'); });
      });
    }

    var taskSelect = $('#task-select');
    if (taskSelect) {
      taskSelect.addEventListener('change', function () {
        if (taskSelect.value) loadTask(taskSelect.value);
      });
    }

    var auditSelect = $('#audit-task-select');
    if (auditSelect) {
      auditSelect.addEventListener('change', function () {
        state.auditLoadedFor = '';
        loadAudit(auditSelect.value);
      });
    }

    // 未捕获异常也要可见，不静默失败
    window.addEventListener('error', function (ev) {
      if (ev && ev.message) notify('页面脚本异常：' + ev.message, 'error');
    });
    window.addEventListener('unhandledrejection', function (ev) {
      var reason = ev && ev.reason;
      notify('未处理的异步异常：' + (reason && reason.message ? reason.message : str(reason)), 'error');
    });
  }

  /* ======================================================================
   * 11. 初始化
   * ==================================================================== */

  async function init() {
    try {
      var authResponse = await fetch('/api/auth/me');
      if (!authResponse.ok) { location.href='/login.html'; return; }
      currentUser = await authResponse.json();
      csrfToken = await (await fetch('/api/auth/csrf')).json();
      var sessionBar = document.createElement('div');
      sessionBar.style.cssText='padding:10px 24px;background:#e7f1ef;color:#193c37;display:flex;gap:16px;align-items:center;position:relative;z-index:5';
      var identity = document.createElement('span');
      identity.textContent='当前账号：'+currentUser.username+' · '+currentUser.roles.join(', ')+' | 演示业务：不连接真实支付';
      var logout = document.createElement('button'); logout.textContent='退出 / 切换账号'; logout.className='btn btn-sm';
      logout.onclick=async function() { await fetch('/api/logout',{method:'POST',headers:{[csrfToken.headerName]:csrfToken.token}});location.href='/login.html'; };
      var password = document.createElement('a'); password.href='/login.html#password';password.textContent='修改密码';
      sessionBar.append(identity,password,logout); document.body.prepend(sessionBar);
    } catch(e) { document.body.textContent='登录状态加载失败，请刷新页面';return; }
    bindStaticEvents();

    // 预填示例 goal
    var goal = $('#goal-input');
    if (goal && !str(goal.value).trim()) goal.value = SAMPLES.complaint;

    setBackendState('unknown', '正在检测后端连通性');

    // 先渲染空态：即使后端完全不可达，详情区也不会是一片空白
    renderTaskDetail(null);

    // 首屏：组织架构 + 任务列表并行拉取，互不阻塞
    loadOrg();
    refreshTasks().then(function () {
      // 有历史任务时自动载入最新一条，让控制台一打开就是「活」的
      if (!state.currentTask && state.tasks.length) {
        var first = obj(state.tasks[0]);
        if (str(first.taskId)) {
          loadTask(str(first.taskId));
          state.auditTaskId = str(first.taskId);
          renderTaskOptions();
        }
      }
    });
  }

  if (document.readyState === 'loading') document.addEventListener('DOMContentLoaded', init);
  else init();

})();
