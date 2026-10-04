import assert from 'node:assert/strict';
import fs from 'node:fs';
import vm from 'node:vm';
const source = fs.readFileSync('src/main/resources/static/admin.js', 'utf8');
const context = vm.createContext({ document: {querySelector: () => ({}), querySelectorAll: () => []}, Intl, URL });
vm.runInContext(source.slice(0, source.indexOf('let lastCreatedShortUrl')) + source.slice(source.indexOf('function errorMessage'), source.indexOf('function formatDateTime')), context);
function message(status, code, purpose, retryAfter) {
  context.status = status; context.code = code; context.purpose = purpose; context.retryAfter = retryAfter;
  return vm.runInContext('errorMessage(new ApiRequestError(status, {code, shortCode: "Ab12"}, retryAfter), purpose)', context);
}
assert.match(message(429, 'RATE_LIMIT_EXCEEDED', 'state', '2'), /2.*秒/);
assert.match(message(503, 'RATE_LIMIT_UNAVAILABLE', 'state'), /未开始|尚未开始/);
assert.match(message(503, 'RATE_LIMIT_UNAVAILABLE', 'stats'), /未开始|尚未开始/);
assert.doesNotMatch(message(503, 'RATE_LIMIT_UNAVAILABLE', 'state'), /已提交/);
assert.match(message(503, 'LINK_STATE_CACHE_COORDINATION_UNCONFIRMED', 'state'), /已提交.*数据库/);
assert.match(message(503, 'STATS_BUSY', 'stats'), /繁忙/);
assert.match(message(503, 'STATS_QUERY_TIMEOUT', 'stats'), /超时/);
assert.doesNotMatch(source, /localStorage|sessionStorage/);
console.log('Management page admission and committed-error contracts passed.');
