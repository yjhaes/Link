const tokenInput = document.querySelector('#internal-token');
const clearTokenButton = document.querySelector('#clear-token-button');
const tokenHint = document.querySelector('#token-hint');

const createForm = document.querySelector('#admin-create-form');
const originalUrlInput = document.querySelector('#original-url');
const expiryModeInputs = [...document.querySelectorAll('input[name="expiry-mode"]')];
const durationField = document.querySelector('#duration-field');
const validMinutesInput = document.querySelector('#valid-minutes');
const createButton = document.querySelector('#create-button');
const createButtonLabel = document.querySelector('#create-button-label');
const createError = document.querySelector('#create-error');
const resultEmpty = document.querySelector('#result-empty');
const resultContent = document.querySelector('#result-content');
const resultSummary = document.querySelector('#result-summary');
const createResultState = document.querySelector('#create-result-state');
const resultShortUrl = document.querySelector('#result-short-url');
const openResultLink = document.querySelector('#open-result-link');
const resultShortCode = document.querySelector('#result-short-code');
const resultExpiresAt = document.querySelector('#result-expires-at');
const resultOriginalUrl = document.querySelector('#result-original-url');
const copyResultButton = document.querySelector('#copy-result-button');
const copyMessage = document.querySelector('#copy-message');
const manageCreatedCodeButton = document.querySelector('#manage-created-code');

const stateForm = document.querySelector('#state-form');
const managedCodeInput = document.querySelector('#managed-code');
const stateCurrentValue = document.querySelector('#state-current-value');
const stateMessage = document.querySelector('#state-message');
const stateError = document.querySelector('#state-error');
const stateButtons = [...document.querySelectorAll('.state-actions button')];

const statsCodeLabel = document.querySelector('#stats-code-label');
const statsFromInput = document.querySelector('#stats-from');
const statsToInput = document.querySelector('#stats-to');
const queryStatsButton = document.querySelector('#query-stats-button');
const queryButtonLabel = document.querySelector('#query-button-label');
const statsRangeError = document.querySelector('#stats-range-error');
const collectionNotice = document.querySelector('#collection-notice');
const rangePv = document.querySelector('#range-pv');
const rangeUv = document.querySelector('#range-uv');
const statsMeta = document.querySelector('#stats-meta');
const statsMessage = document.querySelector('#stats-message');
const dailyRows = document.querySelector('#daily-rows');
const retryStatsButton = document.querySelector('#retry-stats-button');
const visitRows = document.querySelector('#visit-rows');
const visitCount = document.querySelector('#visit-count');
const visitsMessage = document.querySelector('#visits-message');
const retryVisitsButton = document.querySelector('#retry-visits-button');
const loadMoreButton = document.querySelector('#load-more-button');

const shortCodePattern = /^[A-Za-z0-9]{4,8}$/;
const dateFormatter = new Intl.DateTimeFormat('zh-CN', {
  timeZone: 'Asia/Shanghai',
  year: 'numeric', month: '2-digit', day: '2-digit',
  hour: '2-digit', minute: '2-digit', second: '2-digit', hour12: false,
});
const numberFormatter = new Intl.NumberFormat('zh-CN');

class ApiRequestError extends Error {
  constructor(status, body) {
    super(body?.message || `HTTP ${status}`);
    this.name = 'ApiRequestError';
    this.status = status;
    this.code = body?.code;
    this.body = body;
  }
}

let lastCreatedShortUrl = '';
let lastCreatedCode = '';
let statsGeneration = 0;
const statsControllers = new Set();
let visitContext = null;
let nextVisitCursor = null;
let visitHasMore = false;
let visitRowCount = 0;
let visitRequestBusy = false;

async function requestJson(url, options = {}) {
  const response = await fetch(url, options);
  const body = await response.json().catch(() => null);
  if (!response.ok) throw new ApiRequestError(response.status, body);
  return body;
}

function selectedExpiryMode() {
  return expiryModeInputs.find((input) => input.checked)?.value ?? 'permanent';
}

function updateExpiryMode() {
  const isLimited = selectedExpiryMode() === 'limited';
  durationField.hidden = !isLimited;
  validMinutesInput.disabled = !isLimited;
  validMinutesInput.required = isLimited;
  if (!isLimited) validMinutesInput.removeAttribute('aria-invalid');
}

function validateOriginalUrl(value) {
  if (!value || value.trim().length === 0) return { message: '请输入原始网址。', field: originalUrlInput };
  if (value.length > 4096) return { message: '网址不能超过 4096 个字符。', field: originalUrlInput };
  if (value !== value.trim()) return { message: '网址开头或结尾不能带空格。', field: originalUrlInput };
  let parsedUrl;
  try { parsedUrl = new URL(value); } catch {
    return { message: '请输入完整的网址，例如 https://example.com。', field: originalUrlInput };
  }
  if (!['http:', 'https:'].includes(parsedUrl.protocol) || !parsedUrl.hostname) {
    return { message: '网址需要以 http:// 或 https:// 开头，并包含主机名。', field: originalUrlInput };
  }
  if (parsedUrl.username || parsedUrl.password) {
    return { message: '网址不能包含用户名或密码。', field: originalUrlInput };
  }
  return null;
}

function validateValidMinutes() {
  const value = validMinutesInput.value;
  if (!/^\d+$/.test(value)) return { message: '请输入有效的整数分钟数。', field: validMinutesInput };
  const minutes = Number(value);
  if (!Number.isSafeInteger(minutes) || minutes < 1 || minutes > 5_256_000) {
    return { message: '有效分钟数需在 1 至 5,256,000 之间。', field: validMinutesInput };
  }
  return null;
}

function clearCreateMessage() {
  createError.textContent = '';
  createError.hidden = true;
  createError.classList.remove('is-warning');
  originalUrlInput.removeAttribute('aria-invalid');
  validMinutesInput.removeAttribute('aria-invalid');
}

function showCreateMessage(message, field = null, warning = false) {
  createError.textContent = message;
  createError.hidden = false;
  createError.classList.toggle('is-warning', warning);
  originalUrlInput.removeAttribute('aria-invalid');
  validMinutesInput.removeAttribute('aria-invalid');
  if (field) {
    field.setAttribute('aria-invalid', 'true');
    field.focus();
  }
}

function errorMessage(error, purpose) {
  if (error instanceof TypeError && !(error instanceof ApiRequestError)) {
    if (purpose === 'create') return '未收到创建确认，服务器可能已创建短链接。重复提交会生成新的映射，请先确认结果再操作。';
    if (purpose === 'state') return '未收到状态操作确认，提交结果暂时无法确定。请联系维护者核实后再操作。';
    return '无法连接到服务，请检查网络或服务状态后重试。';
  }
  if (!(error instanceof ApiRequestError)) {
    return purpose === 'create' ? '创建失败，请检查输入或服务状态后重试。' : '请求失败，请检查输入或服务状态后重试。';
  }
  if (error.status === 401 || error.code === 'INTERNAL_UNAUTHORIZED') return '管理令牌无效，请检查后重新输入。';
  if (error.status === 404 && error.code === 'RESOURCE_NOT_FOUND') return '管理接口尚未启用，请让维护者配置 SHORT_LINK_INTERNAL_TOKEN。';
  if (error.status === 404 && error.code === 'LINK_NOT_FOUND') return '没有找到这个短码，请检查短码后重试。';
  if (error.status === 410 || error.code === 'LINK_EXPIRED') return '这个短链接已过期，不能再调整状态。';
  if (error.code === 'LINK_DISABLED') return '这个短链接已禁用，无法完成当前操作。';
  if (error.code === 'LINK_ALREADY_ENABLED') return '这个短链接已经启用，无需重复操作。';
  if (error.code === 'LINK_ALREADY_DISABLED') return '这个短链接已经禁用，无需重复操作。';
  if (error.code === 'CREATE_CACHE_COORDINATION_UNCONFIRMED') {
    return `短码 ${error.body?.shortCode || '未知'} 已提交到数据库，但缓存协调尚未确认。请保留短码并联系维护者恢复，不要重新提交创建。`;
  }
  if (error.code === 'LINK_STATE_CACHE_COORDINATION_UNCONFIRMED') {
    return `短码 ${error.body?.shortCode || '未知'} 的状态已提交到数据库，但缓存协调尚未确认。请保留短码并联系维护者恢复，不要自动重复操作。`;
  }
  if (error.code === 'STATS_BUSY') return '统计查询繁忙，请稍后重新查询。';
  if (error.code === 'STATS_QUERY_TIMEOUT') return '统计查询超时，请稍后重新查询。';
  if (error.code === 'INVALID_REQUEST') {
    if (purpose === 'create') return '网址或有效分钟数未通过服务端校验，请检查输入后重试。';
    return '请求参数无效，请检查短码和统计日期范围。';
  }
  if (error.status >= 500 || error.code === 'INTERNAL_ERROR') {
    if (purpose === 'create') return '服务器暂时无法创建短链接，请稍后重试。';
    if (purpose === 'stats') return '统计服务暂时无法完成查询，请稍后重试。';
    return '服务器暂时无法完成操作，请稍后重试。';
  }
  return purpose === 'create' ? '创建失败，请检查输入后重试。' : '请求失败，请检查输入后重试。';
}

function formatDateTime(value) {
  const date = new Date(value);
  return Number.isNaN(date.getTime()) ? String(value ?? '—') : `${dateFormatter.format(date)}（上海时间）`;
}

function validShortLinkUrl(url, code) {
  const fallback = `${window.location.origin}/s/${encodeURIComponent(code)}`;
  if (!url) return fallback;
  try {
    const parsed = new URL(url, window.location.origin);
    return ['http:', 'https:'].includes(parsed.protocol) ? parsed.href : fallback;
  } catch { return fallback; }
}

function renderCreatedResult(data, originalUrl, partial = false) {
  const code = typeof data.shortCode === 'string' ? data.shortCode : '';
  const shortUrl = validShortLinkUrl(data.shortUrl, code);
  lastCreatedShortUrl = shortUrl;
  lastCreatedCode = code;
  resultEmpty.hidden = true;
  resultContent.hidden = false;
  resultShortUrl.href = shortUrl;
  resultShortUrl.textContent = shortUrl;
  openResultLink.href = shortUrl;
  resultShortCode.textContent = code || '未返回';
  resultExpiresAt.textContent = partial
    ? '服务端未返回，协调完成后请维护者确认'
    : data.expiresAt ? formatDateTime(data.expiresAt) : '永久有效';
  resultOriginalUrl.textContent = originalUrl;
  copyMessage.textContent = '';
  createResultState.textContent = partial ? '已提交 / 待恢复' : '创建已确认';
  createResultState.classList.toggle('is-ready', !partial);
  createResultState.classList.toggle('is-partial', partial);
  resultSummary.textContent = partial ? '映射已写入数据库；缓存协调仍需维护者恢复。' : '短链接已创建，可以复制或打开。';
}

function resetCreateResult() {
  resultEmpty.hidden = false;
  resultContent.hidden = true;
  createResultState.textContent = '等待创建';
  createResultState.classList.remove('is-ready', 'is-partial');
  resultSummary.textContent = '生成的短链接会显示在这里。';
  resultShortUrl.removeAttribute('href');
  openResultLink.removeAttribute('href');
  resultShortUrl.textContent = '';
  copyMessage.textContent = '';
  lastCreatedShortUrl = '';
  lastCreatedCode = '';
}

createForm.addEventListener('submit', async (event) => {
  event.preventDefault();
  clearCreateMessage();
  const originalUrl = originalUrlInput.value;
  const urlError = validateOriginalUrl(originalUrl);
  if (urlError) return showCreateMessage(urlError.message, urlError.field);
  const requestBody = { originalUrl };
  if (selectedExpiryMode() === 'limited') {
    const durationError = validateValidMinutes();
    if (durationError) return showCreateMessage(durationError.message, durationError.field);
    requestBody.validMinutes = Number(validMinutesInput.value);
  }

  resetCreateResult();
  createButton.disabled = true;
  createForm.setAttribute('aria-busy', 'true');
  createButtonLabel.textContent = '正在创建…';
  try {
    const response = await fetch('/api/links', {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify(requestBody),
    });
    const data = await response.json().catch(() => null);
    if (!response.ok) {
      const error = new ApiRequestError(response.status, data);
      if (error.code === 'CREATE_CACHE_COORDINATION_UNCONFIRMED' && data?.shortCode) {
        renderCreatedResult(data, originalUrl, true);
        showCreateMessage(errorMessage(error, 'create'), null, true);
        return;
      }
      throw error;
    }
    renderCreatedResult(data, originalUrl);
  } catch (error) {
    showCreateMessage(errorMessage(error, 'create'));
  } finally {
    createButton.disabled = false;
    createForm.removeAttribute('aria-busy');
    createButtonLabel.textContent = '生成短链接';
  }
});

expiryModeInputs.forEach((input) => input.addEventListener('change', updateExpiryMode));

async function copyText(value) {
  if (navigator.clipboard?.writeText) {
    await navigator.clipboard.writeText(value);
    return;
  }
  const textarea = document.createElement('textarea');
  textarea.value = value;
  textarea.setAttribute('readonly', '');
  textarea.style.position = 'fixed';
  textarea.style.opacity = '0';
  document.body.append(textarea);
  textarea.select();
  const copied = document.execCommand('copy');
  textarea.remove();
  if (!copied) throw new Error('Clipboard unavailable');
}

copyResultButton.addEventListener('click', async () => {
  if (!lastCreatedShortUrl) return;
  copyResultButton.disabled = true;
  copyMessage.textContent = '';
  try {
    await copyText(lastCreatedShortUrl);
    copyMessage.textContent = '短链接已复制到剪贴板。';
  } catch {
    copyMessage.textContent = '复制失败，请选择上方短链接手动复制。';
  } finally { copyResultButton.disabled = false; }
});

function clearStateError() {
  stateError.textContent = '';
  stateError.hidden = true;
  stateError.classList.remove('is-warning');
}

function showStateError(message, warning = false) {
  stateError.textContent = message;
  stateError.hidden = false;
  stateError.classList.toggle('is-warning', warning);
}

function setStateResult(code, enabled, label = null) {
  stateCurrentValue.textContent = `${code} · ${label || (enabled ? '已启用' : '已禁用')}`;
  stateCurrentValue.classList.toggle('is-enabled', enabled && !label);
  stateCurrentValue.classList.toggle('is-disabled', !enabled && !label);
}

function setStateUnknown() {
  stateCurrentValue.textContent = '未知';
  stateCurrentValue.classList.remove('is-enabled', 'is-disabled');
  stateMessage.textContent = '';
  clearStateError();
}

function validateManagedCode() {
  const code = managedCodeInput.value;
  if (!shortCodePattern.test(code)) {
    managedCodeInput.setAttribute('aria-invalid', 'true');
    managedCodeInput.focus();
    return false;
  }
  managedCodeInput.removeAttribute('aria-invalid');
  return true;
}

async function changeLinkState(enabled) {
  clearStateError();
  stateMessage.textContent = '';
  if (!validateManagedCode()) {
    showStateError('请输入 4 至 8 位英文字母或数字组成的短码。');
    return;
  }
  const token = tokenInput.value;
  if (!token) {
    showStateError('请先输入管理令牌。');
    tokenInput.focus();
    return;
  }

  const code = managedCodeInput.value;
  stateButtons.forEach((button) => { button.disabled = true; });
  stateMessage.textContent = `正在提交 ${code} 的${enabled ? '启用' : '禁用'}操作…`;
  try {
    const data = await requestJson(`/api/links/${encodeURIComponent(code)}/enabled`, {
      method: 'PUT',
      headers: { 'Content-Type': 'application/json', 'X-Internal-Token': token },
      body: JSON.stringify({ enabled }),
    });
    setStateResult(data.shortCode || code, data.enabled);
    stateMessage.textContent = `服务端已确认 ${data.shortCode || code} 本次操作：${data.enabled ? '启用' : '禁用'}。`;
  } catch (error) {
    if (error instanceof ApiRequestError && error.code === 'LINK_ALREADY_ENABLED') {
      setStateResult(code, true);
      stateMessage.textContent = '';
      showStateError(errorMessage(error, 'state'));
    } else if (error instanceof ApiRequestError && error.code === 'LINK_ALREADY_DISABLED') {
      setStateResult(code, false);
      stateMessage.textContent = '';
      showStateError(errorMessage(error, 'state'));
    } else if (error instanceof ApiRequestError && error.code === 'LINK_STATE_CACHE_COORDINATION_UNCONFIRMED') {
      const savedCode = error.body?.shortCode || code;
      managedCodeInput.value = savedCode;
      statsCodeLabel.textContent = savedCode;
      setStateResult(savedCode, enabled, `本次已提交${enabled ? '启用' : '禁用'}，缓存待恢复`);
      stateCurrentValue.classList.remove('is-enabled', 'is-disabled');
      stateMessage.textContent = '';
      showStateError(errorMessage(error, 'state'), true);
    } else {
      stateMessage.textContent = '';
      showStateError(errorMessage(error, 'state'));
    }
  } finally { stateButtons.forEach((button) => { button.disabled = false; }); }
}

stateButtons.forEach((button) => {
  button.addEventListener('click', () => changeLinkState(button.dataset.enabled === 'true'));
});
stateForm.addEventListener('submit', (event) => event.preventDefault());

function setShanghaiDateRangeDefaults() {
  const parts = new Intl.DateTimeFormat('en', {
    timeZone: 'Asia/Shanghai', year: 'numeric', month: '2-digit', day: '2-digit',
  }).formatToParts(new Date());
  const values = Object.fromEntries(parts.map((part) => [part.type, part.value]));
  const today = `${values.year}-${values.month}-${values.day}`;
  const [year, month, day] = today.split('-').map(Number);
  const todayUtc = Date.UTC(year, month - 1, day);
  const formatUtcDay = (stamp) => {
    const date = new Date(stamp);
    return [date.getUTCFullYear(), String(date.getUTCMonth() + 1).padStart(2, '0'), String(date.getUTCDate()).padStart(2, '0')].join('-');
  };
  const earliest = formatUtcDay(todayUtc - 29 * 24 * 60 * 60 * 1000);
  statsFromInput.min = earliest;
  statsFromInput.max = today;
  statsToInput.min = earliest;
  statsToInput.max = today;
  statsFromInput.value = formatUtcDay(todayUtc - 6 * 24 * 60 * 60 * 1000);
  statsToInput.value = today;
}

function clearTable(tbody, columns, message) {
  const row = document.createElement('tr');
  const cell = document.createElement('td');
  cell.colSpan = columns;
  cell.className = 'table-empty';
  cell.textContent = message;
  row.append(cell);
  tbody.replaceChildren(row);
}

function clearStatsResults(message = '') {
  statsGeneration += 1;
  for (const controller of statsControllers) controller.abort();
  statsControllers.clear();
  visitContext = null;
  nextVisitCursor = null;
  visitHasMore = false;
  visitRowCount = 0;
  visitRequestBusy = false;
  queryStatsButton.disabled = false;
  queryButtonLabel.textContent = '查询访问数据';
  statsRangeError.textContent = '';
  statsRangeError.hidden = true;
  rangePv.textContent = '—';
  rangeUv.textContent = '—';
  statsMeta.textContent = '选择短码和日期范围后查询。';
  statsMessage.textContent = message;
  visitsMessage.textContent = '';
  visitCount.textContent = '';
  collectionNotice.hidden = true;
  retryStatsButton.hidden = true;
  retryVisitsButton.hidden = true;
  loadMoreButton.hidden = true;
  loadMoreButton.disabled = false;
  loadMoreButton.textContent = '加载更多';
  clearTable(dailyRows, 4, '尚无查询结果');
  clearTable(visitRows, 4, '尚无查询结果');
}

function currentCodeLabel() {
  const code = managedCodeInput.value;
  statsCodeLabel.textContent = shortCodePattern.test(code) ? code : '尚未选择';
}

function dateToUtcDay(value) {
  const match = /^(\d{4})-(\d{2})-(\d{2})$/.exec(value);
  return match ? Date.UTC(Number(match[1]), Number(match[2]) - 1, Number(match[3])) : Number.NaN;
}

function dateRangeError() {
  const from = statsFromInput.value;
  const to = statsToInput.value;
  if (!from || !to) return '请选择开始日期和结束日期。';
  const fromDay = dateToUtcDay(from);
  const toDay = dateToUtcDay(to);
  if (!Number.isFinite(fromDay) || !Number.isFinite(toDay)) return '请选择有效的统计日期。';
  const earliest = statsFromInput.min;
  const latest = statsFromInput.max;
  if (from < earliest || from > latest || to < earliest || to > latest) {
    return '日期需在最近 30 个上海统计日内，并且不能晚于今天。';
  }
  if (fromDay > toDay) return '开始日期不能晚于结束日期。';
  if ((toDay - fromDay) / (24 * 60 * 60 * 1000) >= 30) return '日期范围最多包含 30 个上海统计日。';
  return null;
}

function addTableCell(row, value, className = '') {
  const cell = document.createElement('td');
  cell.textContent = value == null || value === '' ? '未记录' : String(value);
  if (className) cell.className = className;
  row.append(cell);
  return cell;
}

function renderDailyStats(data) {
  const days = Array.isArray(data.daily) ? data.daily : [];
  if (days.length === 0) {
    clearTable(dailyRows, 4, '此范围暂无已记录访问事件。');
    return;
  }
  const fragment = document.createDocumentFragment();
  for (const day of days) {
    const row = document.createElement('tr');
    addTableCell(row, day.date);
    addTableCell(row, numberFormatter.format(day.pv ?? 0), 'numeric-cell');
    addTableCell(row, numberFormatter.format(day.uv ?? 0), 'numeric-cell');
    const statusCell = addTableCell(row, day.isOngoing ? '进行中' : '已结束', `status-cell${day.isOngoing ? ' is-ongoing' : ''}`);
    statusCell.setAttribute('aria-label', day.isOngoing ? '统计日仍在进行' : '统计日已结束');
    fragment.append(row);
  }
  dailyRows.replaceChildren(fragment);
}

function formatOccurredAt(value) {
  const date = new Date(value);
  return Number.isNaN(date.getTime()) ? String(value ?? '未记录') : dateFormatter.format(date);
}

function renderVisitItems(items, append = false) {
  const safeItems = Array.isArray(items) ? items : [];
  if (!append && safeItems.length === 0) {
    clearTable(visitRows, 4, '此范围暂无访问日志。');
    visitRowCount = 0;
    visitCount.textContent = '0 条';
    return;
  }
  const fragment = document.createDocumentFragment();
  for (const item of safeItems) {
    const row = document.createElement('tr');
    addTableCell(row, formatOccurredAt(item.occurredAt));
    addTableCell(row, item.peerIpNetwork ?? '未记录');
    addTableCell(row, item.refererHost ?? '直接访问 / 未记录');
    addTableCell(row, item.userAgent ?? '未记录');
    fragment.append(row);
  }
  if (append) visitRows.append(fragment);
  else visitRows.replaceChildren(fragment);
  visitRowCount += safeItems.length;
  visitCount.textContent = `已显示 ${numberFormatter.format(visitRowCount)} 条`;
}

function setVisitPaging(data) {
  nextVisitCursor = typeof data.nextCursor === 'string' && data.nextCursor ? data.nextCursor : null;
  visitHasMore = data.hasMore === true && Boolean(nextVisitCursor);
  loadMoreButton.hidden = !visitHasMore;
  loadMoreButton.disabled = false;
  loadMoreButton.textContent = '加载更多';
}

function queryUrl(endpoint, context, cursor = null) {
  const parameters = new URLSearchParams({ from: context.from, to: context.to });
  if (endpoint === 'visits') {
    parameters.set('limit', '20');
    if (cursor) parameters.set('cursor', cursor);
  }
  return `/api/internal/links/${encodeURIComponent(context.code)}/${endpoint}?${parameters.toString()}`;
}

function registerStatsController(controller) {
  statsControllers.add(controller);
  return controller;
}

function releaseStatsController(controller) {
  statsControllers.delete(controller);
}

function showStatsFailure(error) {
  statsMessage.textContent = errorMessage(error, 'stats');
  retryStatsButton.hidden = false;
}

async function queryStats() {
  statsRangeError.textContent = '';
  statsRangeError.hidden = true;
  statsFromInput.removeAttribute('aria-invalid');
  statsToInput.removeAttribute('aria-invalid');

  if (!validateManagedCode()) {
    statsRangeError.textContent = '请先在“状态管理”中输入 4 至 8 位短码。';
    statsRangeError.hidden = false;
    managedCodeInput.focus();
    return;
  }
  const rangeError = dateRangeError();
  if (rangeError) {
    statsRangeError.textContent = rangeError;
    statsRangeError.hidden = false;
    (statsFromInput.value ? statsToInput : statsFromInput).setAttribute('aria-invalid', 'true');
    return;
  }
  const token = tokenInput.value;
  if (!token) {
    statsMessage.textContent = '请先输入管理令牌。';
    tokenInput.focus();
    return;
  }

  clearStatsResults('正在查询统计和访问日志…');
  const generation = statsGeneration;
  const context = { code: managedCodeInput.value, from: statsFromInput.value, to: statsToInput.value, token, generation };
  visitContext = context;
  queryStatsButton.disabled = true;
  queryButtonLabel.textContent = '正在查询…';
  const controller = registerStatsController(new AbortController());
  const headers = { 'X-Internal-Token': context.token };
  const results = await Promise.allSettled([
    requestJson(queryUrl('stats', context), { headers, signal: controller.signal }),
    requestJson(queryUrl('visits', context), { headers, signal: controller.signal }),
  ]);
  releaseStatsController(controller);
  if (generation !== statsGeneration || controller.signal.aborted) return;

  const [statsResult, visitsResult] = results;
  if (statsResult.status === 'fulfilled') {
    const data = statsResult.value;
    rangePv.textContent = numberFormatter.format(data.pv ?? 0);
    rangeUv.textContent = numberFormatter.format(data.uv ?? 0);
    statsMeta.textContent = `统计范围 ${data.from} 至 ${data.to} · ${data.timeZone || 'Asia/Shanghai'} · 生成于 ${formatDateTime(data.generatedAt)}`;
    collectionNotice.hidden = data.collectionEnabled !== false;
    statsMessage.textContent = data.collectionEnabled === false
      ? '访问采集已关闭；此查询只展示关闭前已记录的访问事件。'
      : '统计读取已完成。刚发生的访问可能仍在异步写入。';
    renderDailyStats(data);
  } else if (statsResult.reason?.name !== 'AbortError') {
    showStatsFailure(statsResult.reason);
  }

  if (visitsResult.status === 'fulfilled') {
    const data = visitsResult.value;
    renderVisitItems(data.items, false);
    setVisitPaging(data);
    visitsMessage.textContent = data.hasMore ? '还有更多访问记录。' : '已显示此范围内的全部访问记录。';
    retryVisitsButton.hidden = true;
  } else if (visitsResult.reason?.name !== 'AbortError') {
    clearTable(visitRows, 4, '日志查询失败。');
    visitCount.textContent = '';
    visitsMessage.textContent = errorMessage(visitsResult.reason, 'stats');
    retryVisitsButton.hidden = false;
  }
  queryStatsButton.disabled = false;
  queryButtonLabel.textContent = '重新查询';
}

async function requestVisitPage(append) {
  const context = visitContext;
  if (!context || context.generation !== statsGeneration || visitRequestBusy) return;
  const cursor = append ? nextVisitCursor : null;
  if (append && !cursor) return;

  visitRequestBusy = true;
  if (append) {
    loadMoreButton.disabled = true;
    loadMoreButton.textContent = '正在加载…';
  } else {
    retryVisitsButton.disabled = true;
    visitsMessage.textContent = '正在重新查询访问日志…';
  }
  const controller = registerStatsController(new AbortController());
  try {
    const data = await requestJson(queryUrl('visits', context, cursor), {
      headers: { 'X-Internal-Token': context.token }, signal: controller.signal,
    });
    if (context.generation !== statsGeneration || controller.signal.aborted) return;
    renderVisitItems(data.items, append);
    setVisitPaging(data);
    visitsMessage.textContent = data.hasMore ? '还有更多访问记录。' : '已显示此范围内的全部访问记录。';
    retryVisitsButton.hidden = true;
  } catch (error) {
    if (error?.name !== 'AbortError' && context.generation === statsGeneration) {
      visitsMessage.textContent = errorMessage(error, 'stats');
      if (append) loadMoreButton.textContent = '重试加载更多';
      else retryVisitsButton.hidden = false;
    }
  } finally {
    releaseStatsController(controller);
    if (context.generation === statsGeneration) {
      visitRequestBusy = false;
      retryVisitsButton.disabled = false;
      loadMoreButton.disabled = false;
      if (append && visitHasMore && loadMoreButton.textContent === '正在加载…') loadMoreButton.textContent = '加载更多';
    }
  }
}

queryStatsButton.addEventListener('click', queryStats);
retryStatsButton.addEventListener('click', queryStats);
retryVisitsButton.addEventListener('click', () => requestVisitPage(false));
loadMoreButton.addEventListener('click', () => requestVisitPage(true));

managedCodeInput.addEventListener('input', () => {
  managedCodeInput.removeAttribute('aria-invalid');
  currentCodeLabel();
  setStateUnknown();
  clearStatsResults(managedCodeInput.value ? '短码已变化，请重新查询。' : '');
});

for (const input of [statsFromInput, statsToInput]) {
  input.addEventListener('change', () => {
    statsRangeError.textContent = '';
    statsRangeError.hidden = true;
    input.removeAttribute('aria-invalid');
    clearStatsResults('日期范围已变化，请重新查询。');
  });
}

tokenInput.addEventListener('input', () => {
  tokenHint.textContent = tokenInput.value
    ? '令牌只保留在当前页面内存中；修改令牌后需要重新查询。'
    : '令牌通过 HTTPS 请求头发送。请勿在公共设备上使用。';
  clearStatsResults(tokenInput.value ? '令牌已变化，请重新查询。' : '令牌已清除。');
  clearStateError();
  stateMessage.textContent = '';
});

clearTokenButton.addEventListener('click', () => {
  tokenInput.value = '';
  tokenInput.dispatchEvent(new Event('input', { bubbles: true }));
  tokenInput.focus();
});

manageCreatedCodeButton.addEventListener('click', () => {
  if (!lastCreatedCode) return;
  managedCodeInput.value = lastCreatedCode;
  managedCodeInput.dispatchEvent(new Event('input', { bubbles: true }));
  const behavior = window.matchMedia?.('(prefers-reduced-motion: reduce)').matches ? 'auto' : 'smooth';
  document.querySelector('#state-section').scrollIntoView({ behavior, block: 'start' });
  managedCodeInput.focus({ preventScroll: true });
});

setShanghaiDateRangeDefaults();
updateExpiryMode();
currentCodeLabel();
