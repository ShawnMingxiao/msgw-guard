let rulesCache = [];

async function api(path, options) {
  const opt = Object.assign({}, options || {});
  if (opt.body && typeof opt.body !== 'string') {
    opt.headers = Object.assign({}, opt.headers, { 'Content-Type': 'application/json' });
    opt.body = JSON.stringify(opt.body);
  }
  const response = await fetch('/api' + path, opt);
  const text = await response.text();
  let data = null;
  try { data = text ? JSON.parse(text) : null; } catch (e) { data = text; }
  if (!response.ok) {
    throw new Error((data && data.message) || (typeof data === 'string' && data) || response.statusText);
  }
  return data;
}

function esc(value) {
  return String(value == null ? '' : value)
    .replace(/&/g, '&amp;').replace(/</g, '&lt;').replace(/>/g, '&gt;')
    .replace(/"/g, '&quot;').replace(/'/g, '&#39;');
}

function toast(message) {
  alert(message);
}

async function refreshAll() {
  try {
    const status = await api('/status');
    renderStatus(status);
    await Promise.all([loadJobs(), loadAlerts(), loadRules(), loadAudit()]);
  } catch (e) {
    toast('加载失败：' + e.message);
  }
}

function renderStatus(s) {
  const chip = document.getElementById('statusChip');
  chip.className = 'chip ' + (s.circuitOpen ? 'circuit' : (s.running ? 'running' : 'stopped'));
  chip.textContent = s.circuitOpen ? '熔断中' : (s.running ? '运行中' : '已停止');
  document.getElementById('statScans').textContent = s.todayScans;
  document.getElementById('statReplies').textContent = s.todayReplies;
  document.getElementById('statAlerts').textContent = s.todayAlerts;
  document.getElementById('statFailures').textContent = s.todayFailures;
  document.getElementById('circuitBanner').classList.toggle('hidden', !s.circuitOpen);
  const stopped = document.getElementById('stoppedJobs');
  stopped.innerHTML = s.stoppedJobs.length === 0
    ? '<span class="muted">无</span>'
    : s.stoppedJobs.map(j => '<span class="stopped-item">' + esc(j) +
        '<button class="btn small" onclick="resumeJob(\'' + esc(j) + '\')">恢复处理</button></span>').join('');
}

async function loadJobs() {
  try {
    const jobs = await api('/jobs');
    const body = document.getElementById('jobsBody');
    body.innerHTML = jobs.length === 0
      ? '<tr><td colspan="5" class="muted">暂无数据（当前适配器下没有 MSGW job）</td></tr>'
      : jobs.map(j => '<tr><td>' + esc(j.jobName) + '</td><td>' + esc(j.user) + '</td><td>' +
          esc(j.jobNumber) + '</td><td>' + esc(j.subsystem) + '</td><td>' + esc(j.status) + '</td></tr>').join('');
  } catch (e) {
    document.getElementById('jobsBody').innerHTML = '<tr><td colspan="5" class="muted">读取失败：' + esc(e.message) + '</td></tr>';
  }
}

async function loadAlerts() {
  try {
    const list = await api('/guard/alerts');
    const status = await api('/status');
    document.getElementById('alertCount').textContent = status.unacknowledgedAlerts + ' 未确认';
    const box = document.getElementById('alertsList');
    box.innerHTML = list.length === 0
      ? '<div class="muted">暂无告警</div>'
      : list.slice(0, 30).map(a =>
          '<div class="alert-item ' + esc(a.level) + (a.acknowledged ? ' ack' : '') + '">' +
          '<div class="meta">' + esc(a.timestamp) + ' · ' + esc(a.level) + ' · ' + esc(a.job) + '</div>' +
          '<div>' + esc(a.message) + '</div>' +
          (a.acknowledged ? '' : '<button class="btn small" style="margin-top:6px" onclick="ackAlert(\'' + esc(a.id) + '\')">确认</button>') +
          '</div>').join('');
  } catch (e) { /* ignore */ }
}

async function ackAlert(id) {
  await api('/guard/alerts/' + encodeURIComponent(id) + '/ack', { method: 'POST' });
  await loadAlerts();
}

async function resumeJob(jobKey) {
  await api('/guard/jobs/resume', { method: 'POST', body: { jobKey: jobKey } });
  await refreshAll();
}

async function loadRules() {
  rulesCache = await api('/rules');
  renderRuleTable(rulesCache, 'rulesBody', false);
  renderRuleTable(rulesCache.filter(r => r.scope === 'JOB'), 'fixedBody', true);
}

function renderRuleTable(rules, bodyId, fixed) {
  const body = document.getElementById(bodyId);
  if (rules.length === 0) {
    body.innerHTML = '<tr><td colspan="' + (fixed ? 9 : 10) + '" class="muted">暂无规则</td></tr>';
    return;
  }
  body.innerHTML = rules.map(r => {
    const reply = r.action === 'CUSTOM' ? r.customReply : (r.action === 'IGNORE' ? 'I/配置值' : r.action === 'CANCEL' ? 'C/配置值' : '—');
    const cells = fixed
      ? [r.priority, r.name, r.jobPattern, r.userPattern || '*', r.subsystemPattern || '*', r.action, reply, r.enabled ? '启用' : '停用']
      : [r.priority, r.name, r.scope, r.matchField, r.matcher, (r.patterns || []).join(', '), r.action, reply, r.enabled ? '启用' : '停用'];
    const actions = '<button class="btn small" onclick="openRuleModal(\'' + esc(r.id) + '\')">编辑</button> ' +
      '<button class="btn small" onclick="deleteRule(\'' + esc(r.id) + '\')">删除</button>';
    return '<tr>' + cells.map(c => '<td>' + esc(c) + '</td>').join('') + '<td>' + actions + '</td></tr>';
  }).join('');
}

function openRuleModal(arg) {
  const form = document.getElementById('ruleForm');
  form.reset();
  const title = document.getElementById('ruleModalTitle');
  let rule = null;
  if (typeof arg === 'string' && arg.length > 20) {
    rule = rulesCache.find(r => r.id === arg);
    title.textContent = '编辑规则';
  } else if (arg === 'JOB') {
    title.textContent = '新增固定回复规则';
  } else {
    title.textContent = '新增规则';
  }
  if (rule) {
    for (const name of ['id', 'name', 'priority', 'jobPattern', 'userPattern', 'subsystemPattern',
                        'fuzzyThreshold', 'customReply', 'note']) {
      form.elements[name].value = rule[name] == null ? '' : rule[name];
    }
    ['scope', 'matchField', 'matcher', 'matchMode', 'action'].forEach(n => form.elements[n].value = rule[n]);
    form.elements.enabled.value = String(rule.enabled);
    form.elements.caseSensitive.value = String(rule.caseSensitive);
    form.elements.patterns.value = (rule.patterns || []).join('\n');
  } else if (arg === 'JOB') {
    form.elements.scope.value = 'JOB';
    form.elements.matchField.value = 'JOB_NAME';
    form.elements.matcher.value = 'WILDCARD';
  }
  document.getElementById('ruleModal').classList.remove('hidden');
}

function closeModal() {
  document.getElementById('ruleModal').classList.add('hidden');
}

async function saveRule(event) {
  event.preventDefault();
  const form = document.getElementById('ruleForm');
  const data = Object.fromEntries(new FormData(form));
  data.patterns = (data.patterns || '').split('\n').map(s => s.trim()).filter(Boolean);
  data.priority = Number(data.priority);
  data.fuzzyThreshold = Number(data.fuzzyThreshold);
  data.enabled = data.enabled === 'true';
  data.caseSensitive = data.caseSensitive === 'true';
  try {
    if (data.id) {
      await api('/rules/' + encodeURIComponent(data.id), { method: 'PUT', body: data });
    } else {
      await api('/rules', { method: 'POST', body: data });
    }
    closeModal();
    await loadRules();
  } catch (e) { toast('保存失败：' + e.message); }
}

async function deleteRule(id) {
  if (!confirm('确认删除这条规则？')) return;
  await api('/rules/' + encodeURIComponent(id), { method: 'DELETE' });
  await loadRules();
}

function exportRules() {
  const blob = new Blob([JSON.stringify(rulesCache, null, 2)], { type: 'application/json' });
  const a = document.createElement('a');
  a.href = URL.createObjectURL(blob);
  a.download = 'msgw-rules.json';
  a.click();
}

async function importRules(input) {
  const file = input.files[0];
  if (!file) return;
  const text = await file.text();
  try {
    const list = JSON.parse(text);
    for (const rule of list) {
      await api('/rules', { method: 'POST', body: rule });
    }
    await loadRules();
    toast('已导入 ' + list.length + ' 条规则');
  } catch (e) { toast('导入失败：' + e.message); }
  input.value = '';
}

async function testRule(event) {
  event.preventDefault();
  const data = Object.fromEntries(new FormData(document.getElementById('testForm')));
  try {
    const result = await api('/rules/test', { method: 'POST', body: data });
    const d = result.decision;
    document.getElementById('testResult').innerHTML =
      '命中规则：' + esc(d.matchedRuleName || '无（使用默认动作）') + '\n' +
      '动作：' + esc(d.action) + '\n' +
      '回复内容：' + esc(result.replyText == null ? '（不回复）' : result.replyText) + '\n' +
      '理由：' + esc(d.reason) + '\n' +
      '\n完整结果：\n' + esc(JSON.stringify(result, null, 2));
    document.getElementById('testResult').classList.remove('hidden');
  } catch (e) { toast('试算失败：' + e.message); }
}

async function loadAudit(params) {
  try {
    const query = params || '';
    const list = await api('/audit?limit=200' + query);
    const body = document.getElementById('auditBody');
    body.innerHTML = list.length === 0
      ? '<tr><td colspan="7" class="muted">暂无审计记录</td></tr>'
      : list.map(a => '<tr><td>' + esc(a.timestamp) + '</td><td>' + esc(a.job) + '</td><td>' +
          esc(a.messageId || '') + '<br><span class="muted">' + esc(a.textPreview || '') + '</span></td><td>' +
          esc(a.decision) + '</td><td>' + esc(a.executionStatus) + '</td><td>' + (a.alert ? '是' : '否') +
          '</td><td>' + esc(a.reason || a.error || '') + '</td></tr>').join('');
  } catch (e) { /* ignore */ }
}

async function clearAudit() {
  if (!confirm('确认清空审计日志？')) return;
  await api('/audit', { method: 'DELETE' });
  await loadAudit();
}

async function loadSettings() {
  const s = await api('/settings');
  const form = document.getElementById('settingsForm');
  for (const name of ['adapter', 'ibmiHost', 'ibmiUser', 'ibmiPassword', 'ibmiSubsystem',
                      'messageQueuePathTemplate', 'pollIntervalMs', 'maxRetries', 'failureThreshold',
                      'defaultAction', 'ignoreReply', 'cancelReply', 'retryReply', 'defaultReply']) {
    if (form.elements[name] && s[name] != null) form.elements[name].value = s[name];
  }
  form.elements.dryRun.checked = !!s.dryRun;
  form.elements.allowDestructive.checked = !!s.allowDestructive;
}

async function saveSettings(event) {
  event.preventDefault();
  const form = document.getElementById('settingsForm');
  const data = Object.fromEntries(new FormData(form));
  data.pollIntervalMs = Number(data.pollIntervalMs);
  data.maxRetries = Number(data.maxRetries);
  data.failureThreshold = Number(data.failureThreshold);
  data.dryRun = form.elements.dryRun.checked;
  data.allowDestructive = form.elements.allowDestructive.checked;
  try {
    await api('/settings', { method: 'PUT', body: data });
    toast('设置已保存');
    await refreshAll();
  } catch (e) { toast('保存失败：' + e.message); }
}

async function testConnection() {
  const out = document.getElementById('connResult');
  out.textContent = '测试中...';
  try {
    const r = await api('/ibmi/test', { method: 'POST' });
    out.textContent = r.success ? ('连接成功：' + r.description) : ('连接失败：' + (r.error || '未知错误'));
  } catch (e) { out.textContent = '连接失败：' + e.message; }
}

async function inject(event) {
  event.preventDefault();
  const data = Object.fromEntries(new FormData(document.getElementById('injectForm')));
  const job = await api('/simulator/inject', { method: 'POST', body: data });
  await loadJobs();
  toast('已注入 MSGW：' + job.jobName + '，点击“立即扫描”进行处理');
}

document.querySelectorAll('.nav-item').forEach(btn => {
  btn.addEventListener('click', () => {
    document.querySelectorAll('.nav-item').forEach(b => b.classList.remove('active'));
    document.querySelectorAll('.page').forEach(p => p.classList.remove('active'));
    btn.classList.add('active');
    document.getElementById('page-' + btn.dataset.page).classList.add('active');
    if (btn.dataset.page === 'settings') loadSettings();
    if (btn.dataset.page === 'audit') loadAudit();
    if (btn.dataset.page === 'overview') { loadJobs(); loadAlerts(); }
  });
});

document.getElementById('btnStart').onclick = async () => { await api('/guard/start', { method: 'POST' }); await refreshAll(); };
document.getElementById('btnStop').onclick = async () => { await api('/guard/stop', { method: 'POST' }); await refreshAll(); };
document.getElementById('btnScan').onclick = async () => {
  await api('/guard/scan', { method: 'POST' });
  await refreshAll();
  toast('扫描完成，请查看审计日志与告警');
};
document.getElementById('ruleForm').onsubmit = saveRule;
document.getElementById('testForm').onsubmit = testRule;
document.getElementById('settingsForm').onsubmit = saveSettings;
document.getElementById('injectForm').onsubmit = inject;
document.getElementById('auditFilter').onsubmit = (e) => {
  e.preventDefault();
  const data = Object.fromEntries(new FormData(document.getElementById('auditFilter')));
  const query = (data.job ? '&job=' + encodeURIComponent(data.job) : '') +
                (data.action ? '&action=' + encodeURIComponent(data.action) : '');
  loadAudit(query);
};

refreshAll();
