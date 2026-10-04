import assert from 'node:assert/strict';
import fs from 'node:fs';
import vm from 'node:vm';

const nodes = new Map();
function node(selector) {
  if (!nodes.has(selector)) nodes.set(selector, {
    value: '', hidden: false, disabled: false, textContent: '', listeners: {}, attributes: {},
    classList: {add() {}, remove() {}},
    addEventListener(type, callback) { this.listeners[type] = callback; },
    setAttribute(key, value) { this.attributes[key] = value; },
    removeAttribute(key) { delete this.attributes[key]; },
    getAttribute(key) { return this.attributes[key]; }, focus() {},
  });
  return nodes.get(selector);
}
let response;
const context = vm.createContext({
  document: {querySelector: node, querySelectorAll: () => [{checked:true, value:'permanent', addEventListener() {}}]},
  fetch: async () => response, URL, Intl, navigator: {},
  window: {clearTimeout() {}, setTimeout() {}},
});
vm.runInContext(fs.readFileSync('src/main/resources/static/app.js', 'utf8'), context);
node('#original-url').value = 'https://example.com/';
async function submit(status, code, shortCode, retryAfter) {
  response = {ok:false, status, headers: {get: () => retryAfter}, json: async () => ({code, shortCode})};
  await node('#link-form').listeners.submit({preventDefault() {}});
  return node('#form-error').textContent;
}
assert.match(await submit(429, 'RATE_LIMIT_EXCEEDED', null, '6'), /6.*秒/);
assert.match(await submit(503, 'RATE_LIMIT_UNAVAILABLE'), /未开始|尚未开始/);
assert.doesNotMatch(node('#form-error').textContent, /已保存/);
assert.match(await submit(503, 'CREATE_CACHE_COORDINATION_UNCONFIRMED', 'Ab12'), /Ab12.*已保存/);
console.log('Create page HTTP error contracts passed.');
