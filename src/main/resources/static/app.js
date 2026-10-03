// 表单输入与提交控件
const form = document.querySelector('#link-form');
const originalUrlInput = document.querySelector('#original-url');
const durationField = document.querySelector('#duration-field');
const validMinutesInput = document.querySelector('#valid-minutes');
const expiryModeInputs = [...document.querySelectorAll('input[name="expiry-mode"]')];
const submitButton = document.querySelector('#submit-button');
const buttonLabel = document.querySelector('.button-label');
// 错误反馈
const formError = document.querySelector('#form-error');
// 创建结果
const emptyResult = document.querySelector('#empty-result');
const createdResult = document.querySelector('#created-result');
const resultSubtitle = document.querySelector('#result-subtitle');
const resultState = document.querySelector('#result-state');
const shortUrlLink = document.querySelector('#short-url');
const openLink = document.querySelector('#open-link');
const shortCodeOutput = document.querySelector('#short-code');
const expiresAtOutput = document.querySelector('#expires-at');
const originalUrlOutput = document.querySelector('#original-url-result');
// 复制反馈
const copyButton = document.querySelector('#copy-button');
const copyLabel = document.querySelector('#copy-label');
const copyStatus = document.querySelector('#copy-status');

let copyResetTimer;

// 有效期选择与输入校验
function selectedExpiryMode() {
  return expiryModeInputs.find((input) => input.checked)?.value ?? 'permanent';
}

function updateExpiryMode() {
  const isLimited = selectedExpiryMode() === 'limited';
  durationField.hidden = !isLimited;
  validMinutesInput.disabled = !isLimited;
  validMinutesInput.required = isLimited;
  if (!isLimited) {
    validMinutesInput.removeAttribute('aria-invalid');
  }
}

function validateOriginalUrl(value) {
  if (!value || value.trim().length === 0) {
    return { message: '请输入原始网址。', field: originalUrlInput };
  }
  if (value.length > 4096) {
    return { message: '网址不能超过 4096 个字符。', field: originalUrlInput };
  }
  if (value !== value.trim()) {
    return { message: '网址开头或结尾不能带空格。', field: originalUrlInput };
  }

  let parsedUrl;
  try {
    parsedUrl = new URL(value);
  } catch {
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
  if (!/^\d+$/.test(value)) {
    return { message: '请输入有效的整数分钟数。', field: validMinutesInput };
  }

  const minutes = Number(value);
  if (!Number.isSafeInteger(minutes) || minutes < 1 || minutes > 5_256_000) {
    return { message: '有效分钟数需在 1 至 5,256,000 之间。', field: validMinutesInput };
  }

  return null;
}

// 错误反馈与部分完成提示
function clearError() {
  formError.textContent = '';
  formError.hidden = true;
  originalUrlInput.removeAttribute('aria-invalid');
  validMinutesInput.removeAttribute('aria-invalid');
}

function showError(message, field) {
  formError.textContent = message;
  formError.hidden = false;
  originalUrlInput.removeAttribute('aria-invalid');
  validMinutesInput.removeAttribute('aria-invalid');
  if (field) {
    field.setAttribute('aria-invalid', 'true');
    field.focus();
  }
}

function errorMessageFor(response, data) {
  if (data?.code === 'CREATE_CACHE_COORDINATION_UNCONFIRMED') {
    return `短码 ${data.shortCode} 已保存，但访问状态尚未确认。请保留短码并联系维护者恢复；再次提交会创建新的短链接。`;
  }
  if (response.status === 400 || data?.code === 'INVALID_REQUEST') {
    return '网址或有效分钟数未通过服务端校验，请检查输入后重试。';
  }
  if (response.status >= 500 || data?.code === 'INTERNAL_ERROR'
      || data?.code === 'SHORT_CODE_GENERATION_FAILED') {
    return '服务器暂时无法创建短链接，请稍后重试。';
  }
  return '创建失败，请检查输入后重试。';
}

// 创建结果展示
function showEmptyResult() {
  emptyResult.hidden = false;
  createdResult.hidden = true;
  resultSubtitle.textContent = '短链接会显示在这里';
  resultState.classList.remove('is-ready');
  resultState.innerHTML = '<span class="state-dot" aria-hidden="true"></span>等待生成';
  shortUrlLink.removeAttribute('href');
  openLink.removeAttribute('href');
  shortUrlLink.textContent = '';
  shortCodeOutput.textContent = '—';
  expiresAtOutput.textContent = '—';
  originalUrlOutput.textContent = '';
  copyStatus.textContent = '';
  copyLabel.textContent = '复制短链接';
  window.clearTimeout(copyResetTimer);
}

function formatExpiry(expiresAt) {
  const date = new Date(expiresAt);
  if (Number.isNaN(date.getTime())) {
    return expiresAt;
  }

  const formatted = new Intl.DateTimeFormat('zh-CN', {
    year: 'numeric',
    month: 'short',
    day: 'numeric',
    hour: '2-digit',
    minute: '2-digit',
    timeZoneName: 'short',
  }).format(date);
  return `${formatted}（本地时间）`;
}

function renderCreatedLink(data, originalUrl) {
  if (!data || typeof data !== 'object'
      || typeof data.shortUrl !== 'string' || !data.shortUrl
      || typeof data.shortCode !== 'string' || !data.shortCode
      || !(data.expiresAt === null || typeof data.expiresAt === 'string')) {
    throw new Error('服务器返回的数据不完整，请稍后重试。');
  }

  let shortUrl;
  try {
    shortUrl = new URL(data.shortUrl);
  } catch {
    throw new Error('服务器返回的短链接格式有误，请稍后重试。');
  }
  if (!['http:', 'https:'].includes(shortUrl.protocol)) {
    throw new Error('服务器返回的短链接无法安全打开。');
  }

  shortUrlLink.href = data.shortUrl;
  shortUrlLink.textContent = data.shortUrl;
  openLink.href = data.shortUrl;
  shortCodeOutput.textContent = data.shortCode;
  expiresAtOutput.textContent = data.expiresAt === null ? '永久有效' : formatExpiry(data.expiresAt);
  originalUrlOutput.textContent = originalUrl;
  originalUrlOutput.title = originalUrl;

  emptyResult.hidden = true;
  createdResult.hidden = false;
  resultSubtitle.textContent = '短链接已创建，可复制或打开';
  resultState.classList.add('is-ready');
  resultState.innerHTML = '<span class="state-dot" aria-hidden="true"></span>已生成';
}

// 剪贴板能力与选择复制回退
async function copyToClipboard(value) {
  if (navigator.clipboard?.writeText) {
    try {
      await navigator.clipboard.writeText(value);
      return;
    } catch {
      // Fall through to the selection based copy for browsers without clipboard permission.
    }
  }

  const helper = document.createElement('textarea');
  helper.value = value;
  helper.setAttribute('readonly', '');
  helper.style.position = 'fixed';
  helper.style.opacity = '0';
  document.body.append(helper);
  helper.select();
  const copied = document.execCommand('copy');
  helper.remove();
  if (!copied) {
    throw new Error('Clipboard access is unavailable.');
  }
}

// 输入事件
expiryModeInputs.forEach((input) => input.addEventListener('change', () => {
  updateExpiryMode();
  clearError();
}));
originalUrlInput.addEventListener('input', clearError);
validMinutesInput.addEventListener('input', clearError);

// 创建请求与提交状态
form.addEventListener('submit', async (event) => {
  event.preventDefault();
  if (submitButton.disabled) {
    return;
  }
  clearError();

  const originalUrl = originalUrlInput.value;
  const urlError = validateOriginalUrl(originalUrl);
  if (urlError) {
    showError(urlError.message, urlError.field);
    return;
  }

  const requestBody = { originalUrl };
  if (selectedExpiryMode() === 'limited') {
    const durationError = validateValidMinutes();
    if (durationError) {
      showError(durationError.message, durationError.field);
      return;
    }
    requestBody.validMinutes = Number(validMinutesInput.value);
  }

  showEmptyResult();
  submitButton.disabled = true;
  form.setAttribute('aria-busy', 'true');
  buttonLabel.textContent = '正在创建…';

  try {
    const response = await fetch('/api/links', {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify(requestBody),
    });
    const data = await response.json().catch(() => null);
    if (!response.ok) {
      throw new Error(errorMessageFor(response, data));
    }
    renderCreatedLink(data, originalUrl);
  } catch (error) {
    const message = error instanceof TypeError
      ? '无法连接到服务，请检查网络或服务状态后重试。'
      : error.message || '创建失败，请稍后重试。';
    showError(message);
  } finally {
    submitButton.disabled = false;
    form.removeAttribute('aria-busy');
    buttonLabel.textContent = '生成短链接';
  }
});

// 复制交互与反馈恢复
copyButton.addEventListener('click', async () => {
  const shortUrl = shortUrlLink.getAttribute('href');
  if (!shortUrl) {
    return;
  }

  copyButton.disabled = true;
  copyStatus.textContent = '';
  try {
    await copyToClipboard(shortUrl);
    copyLabel.textContent = '已复制';
    copyStatus.textContent = '短链接已复制到剪贴板。';
    window.clearTimeout(copyResetTimer);
    copyResetTimer = window.setTimeout(() => {
      copyLabel.textContent = '复制短链接';
    }, 1800);
  } catch {
    copyStatus.textContent = '复制失败，请选择上方短链接手动复制。';
  } finally {
    copyButton.disabled = false;
  }
});

// 初始有效期状态
updateExpiryMode();
