'use strict';
const $ = (id) => document.getElementById(id);
let bundle;
let sequence = 0;
let imageSequence = 0;
let objectUrl;
let currentResult;
let currentCardId;
let pending = false;
const labels = {candidates:'候选蛇种，不代表已确认', uncertain:'仍有不确定性，请保留原图', no_snake:'未检测到蛇，不代表现场安全', pending:'识别处理中，由你决定是否查询'};

function node(tag, text, className) {
  const el = document.createElement(tag);
  if (text !== undefined && text !== null) el.textContent = text;
  if (className) el.className = className;
  return el;
}
function safeLink(label, value) {
  try {
    const url = new URL(value);
    if (url.protocol !== 'https:' || url.username || url.password) return null;
    const a = node('a', label);
    a.href = url.href;
    a.target = '_blank';
    a.rel = 'noopener noreferrer';
    return a;
  } catch { return null; }
}
function busy(value) {
  $('run').disabled = value || !bundle;
  $('refresh').disabled = value;
  document.querySelector('.results').setAttribute('aria-busy', String(value));
}
function clearResult(message = '选择一个固定场景，演示候选与物种卡。') {
  sequence += 1;
  currentResult = null;
  currentCardId = null;
  pending = false;
  $('refresh').hidden = true;
  $('catalog').value = '';
  $('result-error').hidden = true;
  $('cards').replaceChildren(node('div', message, 'empty'));
  $('result-source').textContent = '本机离线模拟 · 不产生模型调用';
  $('result-title').textContent = '候选不是结论。';
  $('result-note').textContent = '模拟结果与所选照片无关；参考图只用于设计与比对展示。';
  $('request-id').textContent = '';
  busy(false);
}
function cardElement(speciesId) {
  const card = ($('strict').checked ? bundle.normal : bundle.preview)[speciesId];
  if (!card) return node('div', '当前没有该物种的卡片资料，请保留原图并按未知处理。求助提醒仍可使用。', 'missing');
  const article = node('article', undefined, 'species-card');
  const body = node('div', undefined, 'card-body');
  const head = node('div', undefined, 'card-head');
  const title = node('div');
  title.append(node('h3', card.commonName, 'card-name'));
  if (card.scientificName) title.append(node('div', card.scientificName, 'scientific'));
  head.append(title);
  if (card.previewOnly) head.append(node('span', '演示草稿 · 非实际辨认依据', 'pill draft'));
  body.append(head);
  const badges = node('div', undefined, 'card-badges');
  badges.append(node('span', card.nameStatus === 'verified' ? '名称已核验' : '名称待核验', 'pill neutral'));
  badges.append(node('span', card.contentStatus === 'verified' ? '文案已审核' : card.contentStatus === 'unavailable' ? '文案未整理' : '文案待审核', 'pill neutral'));
  badges.append(node('span', '风险：未知', 'pill neutral'));
  body.append(badges, node('p', card.notice, 'card-notice'));
  if (card.hook) {
    body.append(node('h4', card.hook, 'hook'));
    const list = node('ul', undefined, 'checklist');
    card.checklist.forEach((text) => list.append(node('li', text)));
    body.append(list);
  } else body.append(node('p', '比对说明尚未审核，当前不展示；不能据此判断安全。', 'missing'));
  if (card.images.length) {
    const gallery = node('div', undefined, card.images.length === 1 ? 'gallery single' : 'gallery');
    card.images.forEach((photo) => {
      const figure = node('figure', undefined, 'reference');
      const button = node('button', undefined, 'photo-button');
      button.type = 'button';
      button.setAttribute('aria-label', '放大参考图：' + photo.role);
      const image = node('img');
      image.alt = photo.role;
      const path = new URL(photo.file, location.href);
      if (path.origin !== location.origin || !path.pathname.includes('/data/card_images/')) return;
      image.src = path.href;
      image.loading = 'lazy';
      image.addEventListener('error', () => {
        button.disabled = true;
        button.replaceChildren(node('p', '参考图缺失，不影响求助提醒', 'missing'));
      });
      button.append(image);
      const credit = photo.rights + '；' + photo.source + '；' + photo.modification;
      button.addEventListener('click', () => {
        $('dialog-image').src = path.href;
        $('dialog-image').alt = photo.role;
        $('dialog-credit').textContent = credit;
        $('photo-dialog').showModal();
      });
      const caption = node('figcaption');
      caption.append(node('strong', photo.role));
      caption.append(node('div', photo.reviewStatus === 'verified' ? '参考图已审核' : '参考图待人工核验 · 仅演示'));
      caption.append(node('div', photo.rights), node('div', photo.source), node('div', photo.modification));
      if (photo.sourcePage) {
        const link = safeLink('查看原始来源（需联网）', photo.sourcePage);
        if (link) caption.append(link);
      }
      figure.append(button, caption);
      gallery.append(figure);
    });
    body.append(gallery);
  } else body.append(node('p', card.hiddenImageCount ? '参考图尚未通过审核或来源核对，当前不展示。' : '尚无合适参考图，不以其他物种图片替代。', 'missing'));
  if (card.lookAlikes.length) {
    const details = node('details', undefined, 'lookalikes');
    details.append(node('summary', '易混淆说明（同样待审核）'));
    card.lookAlikes.forEach((item) => details.append(node('p', item.layHowToTell)));
    body.append(details);
  }
  const links = node('div', undefined, 'links');
  card.externalRefs.forEach((ref) => {
    const link = safeLink(ref.name + '（仅供参考，需联网）', ref.url);
    if (link) links.append(link);
  });
  if (links.childElementCount) body.append(links);
  article.append(body, node('div', '不按候选蛇种推导毒性，不生成诊断、用药或处置方案。', 'risk'));
  return article;
}
function renderCards(ids) {
  $('cards').replaceChildren(...ids.map(cardElement));
}
function showResult(result) {
  $('result-error').hidden = true;
  currentResult = result;
  currentCardId = null;
  $('result-source').textContent = '固定响应 · MOCK · 非所选照片的真实识别';
  $('request-id').textContent = result.requestId;
  if (result.error) {
    $('result-title').textContent = '识别没有完成，求助入口仍在。';
    $('result-note').textContent = '此处为错误模拟；不自动重试，不把失败当作没有蛇。';
    $('result-error').textContent = result.error.code + ' · ' + result.error.message;
    $('result-error').hidden = false;
    $('cards').replaceChildren(node('div', '没有可展示的候选。可以保留原图，并直接查看下方通用提醒。', 'empty'));
    pending = false;
  } else {
    $('result-title').textContent = labels[result.status] || '未知结果状态';
    $('result-note').textContent = result.status === 'pending' ? '需要你手动点击查询；本演示没有定时轮询。' : '结果和参考图均不代表对当前照片的物种确认，也不展示概率或准确率。';
    pending = result.status === 'pending';
    if (result.candidates.length) renderCards(result.candidates.map((item) => item.speciesId));
    else $('cards').replaceChildren(node('div', pending ? '等待一次明确的手动查询。期间仍可查看通用求助提醒。' : '无可靠候选，按未知处理。不提供“安全”结论。', 'empty'));
  }
  $('refresh').hidden = !pending;
}
async function runScenario(scenario) {
  const token = ++sequence;
  $('catalog').value = '';
  currentCardId = null;
  currentResult = null;
  pending = false;
  $('refresh').hidden = true;
  $('result-error').hidden = true;
  $('cards').replaceChildren(node('div', '正在读取本机固定响应，不会调用识别模型。', 'empty'));
  busy(true);
  try {
    const response = await fetch('fixtures/' + scenario + '.json', {cache: 'no-store'});
    if (!response.ok) throw new Error('fixture');
    const result = await response.json();
    if (token !== sequence) return;
    if (result.resultSource !== 'mock') throw new Error('source');
    result.requestId = 'demo-' + token;
    showResult(result);
  } catch {
    if (token !== sequence) return;
    $('result-title').textContent = '本地演示资源读取失败';
    $('result-error').textContent = '请检查交付目录是否完整，并通过本机静态服务器打开页面；没有进行模型请求。';
    $('result-error').hidden = false;
  } finally { if (token === sequence) busy(false); }
}
$('run').addEventListener('click', () => runScenario($('scenario').value));
$('refresh').addEventListener('click', () => { if (pending) runScenario('candidates'); });
$('scenario').addEventListener('change', () => clearResult());
$('strict').addEventListener('change', () => {
  if (currentCardId) renderCards([currentCardId]);
  else if (currentResult && !currentResult.error && currentResult.candidates.length) renderCards(currentResult.candidates.map((item) => item.speciesId));
});
$('catalog').addEventListener('change', () => {
  const selected = $('catalog').value;
  if (!selected) return;
  clearResult();
  $('catalog').value = selected;
  currentCardId = selected;
  $('result-source').textContent = '独立卡片设计预览 · 不是识别结果';
  $('result-title').textContent = '把参考图与原图分开标注。';
  $('result-note').textContent = '这里由你指定物种，只展示卡片样式，不判断左侧图片属于什么物种。';
  renderCards([selected]);
});
function clearImage() {
  imageSequence += 1;
  if (objectUrl) URL.revokeObjectURL(objectUrl);
  objectUrl = undefined;
  $('image-input').value = '';
  $('input-preview').removeAttribute('src');
  $('input-preview').hidden = true;
  $('image-placeholder').hidden = false;
  $('image-info').textContent = '不选照片也可演示固定响应。';
  clearResult();
}
$('clear-image').addEventListener('click', clearImage);
$('image-input').addEventListener('change', async () => {
  const file = $('image-input').files[0];
  clearResult('已更换照片，旧模拟结果已清除。');
  const token = ++imageSequence;
  if (objectUrl) URL.revokeObjectURL(objectUrl);
  objectUrl = undefined;
  $('input-preview').removeAttribute('src');
  $('input-preview').hidden = true;
  $('image-placeholder').hidden = false;
  if (!file) { $('image-info').textContent = '不选照片也可演示固定响应。'; return; }
  $('image-info').textContent = '正在准备本机照片预览…';
  try {
    if (file.size > 2_000_000) throw new Error('图片超过 2 MB，请另选一张。');
    const bytes = new Uint8Array(await file.slice(0, 3).arrayBuffer());
    if (bytes[0] !== 255 || bytes[1] !== 216 || bytes[2] !== 255) throw new Error('请选择实际 JPEG 图片。');
    if (token !== imageSequence) return;
    const url = URL.createObjectURL(file);
    objectUrl = url;
    const image = new Image();
    image.src = url;
    await image.decode();
    if (token !== imageSequence) return;
    const {naturalWidth: w, naturalHeight: h} = image;
    if (Math.min(w,h) < 11 || Math.max(w,h) > 8192 || w*h > 16_000_000) throw new Error('图片尺寸超出约定范围，请另选一张。');
    $('input-preview').src = url;
    $('input-preview').hidden = false;
    $('image-placeholder').hidden = true;
    $('image-info').textContent = w + ' × ' + h + ' · ' + Math.ceil(file.size/1024) + ' KB · 仅当前浏览器预览';
  } catch (error) {
    if (token !== imageSequence) return;
    if (objectUrl) URL.revokeObjectURL(objectUrl);
    objectUrl = undefined;
    $('image-info').textContent = error instanceof DOMException ? '图片无法解码，请重新选择。' : error.message;
  }
});
$('help').addEventListener('click', () => { $('safety').scrollIntoView({behavior:'smooth'}); $('safety').focus({preventScroll:true}); });
$('close-dialog').addEventListener('click', () => $('photo-dialog').close());
window.addEventListener('beforeunload', () => { if (objectUrl) URL.revokeObjectURL(objectUrl); });
(async () => {
  try {
    const response = await fetch('species-cards.json');
    if (!response.ok) throw new Error('catalog');
    bundle = await response.json();
    if (bundle.cardSchemaVersion !== '1' || bundle.mode !== 'mock' || bundle.liveCallsEnabled !== false) throw new Error('mode');
    $('safety-list').replaceChildren(...bundle.safety.map((text) => node('li', text)));
    Object.values(bundle.preview).forEach((card) => {
      const option = node('option', card.commonName + (card.nameStatus !== 'verified' ? '（名称待核验）' : ''));
      option.value = card.speciesId;
      $('catalog').append(option);
    });
    const unknown = node('option', '未知物种（缺卡兜底）');
    unknown.value = 'unknown-species';
    $('catalog').append(unknown);
    busy(false);
  } catch {
    bundle = undefined;
    $('result-error').textContent = '卡片数据未能加载。请按交接说明启动本机静态服务器，勿直接双击 HTML；未进行任何模型调用。';
    $('result-error').hidden = false;
    $('catalog').disabled = true;
    busy(false);
  }
})();
