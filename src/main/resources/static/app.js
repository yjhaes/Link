const form = document.querySelector('#link-form');
const input = document.querySelector('#original-url');
const submitButton = document.querySelector('#submit-button');
const buttonLabel = submitButton.querySelector('.button-label');
const formMessage = document.querySelector('#form-message');
const result = document.querySelector('#result');
const shortUrlLink = document.querySelector('#short-url');
const copyButton = document.querySelector('#copy-button');
const copyMessage = document.querySelector('#copy-message');

function showError(message) {
  formMessage.textContent = message;
  formMessage.hidden = false;
  input.setAttribute('aria-invalid', 'true');
}

function clearError() {
  formMessage.textContent = '';
  formMessage.hidden = true;
  input.removeAttribute('aria-invalid');
}

input.addEventListener('input', clearError);

form.addEventListener('submit', async (event) => {
  event.preventDefault();
  clearError();
  result.hidden = true;
  copyMessage.textContent = '';

  const originalUrl = input.value.trim();
  let parsedUrl;
  try {
    parsedUrl = new URL(originalUrl);
  } catch {
    showError('请输入完整的网址，例如 https://example.com');
    input.focus();
    return;
  }
  if (!['http:', 'https:'].includes(parsedUrl.protocol) || !parsedUrl.hostname) {
    showError('链接需要以 http:// 或 https:// 开头。');
    input.focus();
    return;
  }

  submitButton.disabled = true;
  buttonLabel.textContent = '生成中...';

  try {
    const response = await fetch('/api/links', {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ originalUrl }),
    });
    const data = await response.json();
    if (!response.ok) {
      throw new Error(response.status === 400 ? '网址格式有误，请检查后重试。' : '生成失败，请稍后重试。');
    }
    if (typeof data.shortUrl !== 'string' || !data.shortUrl) {
      throw new Error('服务器未返回短链接，请稍后重试。');
    }

    shortUrlLink.href = data.shortUrl;
    shortUrlLink.textContent = data.shortUrl;
    result.hidden = false;
  } catch (error) {
    showError(error.message || '网络连接失败，请稍后重试。');
  } finally {
    submitButton.disabled = false;
    buttonLabel.textContent = '生成短链接';
  }
});

copyButton.addEventListener('click', async () => {
  const shortUrl = shortUrlLink.href;
  try {
    await navigator.clipboard.writeText(shortUrl);
    copyMessage.textContent = '已复制到剪贴板';
  } catch {
    copyMessage.textContent = '复制失败，请手动选择上方链接复制。';
  }
});
