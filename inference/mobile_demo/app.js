'use strict';
const $ = (id) => document.getElementById(id);
const state = { bundle: null, health: null, recognitionId: null, lastResult: null, photoMeta: null, sawUnverified: false };

const HOSPITALS_DEMO = [
  { name: '演示医院 A（占位示例·非真实名录）', phone: '000-0000-0000', note: '生产名录等待有来源的核验资料' },
  { name: '演示医院 B（占位示例·非真实名录）', phone: '000-0000-0001', note: '血清库存不实时、不承诺' },
];

function el(tag, cls, text) {
  const n = document.createElement(tag);
  if (cls) n.className = cls;
  if (text !== undefined) n.textContent = text;
  return n;
}
function base() { return $('cfg-base').value.trim().replace(/\/+$/, ''); }
function token() { return $('cfg-token').value.trim(); }
function strict() { return $('cfg-strict').checked; }

async function loadBundle() {
  try {
    const r = await fetch('species-cards.json');
    if (!r.ok) throw new Error('bundle');
    state.bundle = await r.json();
    renderSafety();
    renderHospitals();
  } catch {
    $('cfg-note').textContent = 'species-cards.json 未加载：请通过代理 /m/ 或导出包目录打开本页。';
  }
}
function renderSafety() {
  const ul = $('safety-list'); ul.replaceChildren();
  (state.bundle?.safety || []).forEach((t) => ul.append(el('li', '', t)));
}
function renderHospitals() {
  const ul = $('hosp-list'); ul.replaceChildren();
  HOSPITALS_DEMO.forEach((h) => {
    const li = el('li', 'hosp');
    li.append(el('div', 'hosp-name', h.name));
    li.append(el('div', 'hosp-note', h.note));
    const row = el('div', 'row');
    const copy = el('button', 'ghost', '复制名称与号码');
    copy.onclick = () => navigator.clipboard?.writeText(`${h.name} ${h.phone}`);
    const dial = el('button', 'ghost', '拨号（演示禁用）');
    dial.disabled = true;
    row.append(copy, dial);
    li.append(row);
    ul.append(li);
  });
}

async function connect() {
  const pill = $('mode-pill');
  if (!base()) {
    state.health = null;
    pill.textContent = '离线模拟';
    pill.className = 'mode offline';
    showOfflineChips(true);
    updateBanner();
    return;
  }
  try {
    const r = await fetch(base() + '/healthz');
    state.health = await r.json();
    const live = state.health.mode === 'hhodata';
    pill.textContent = live ? '代理真实' : '代理 mock';
    pill.className = 'mode ' + (live ? 'live' : 'mock');
    showOfflineChips(false);
  } catch {
    state.health = null;
    pill.textContent = '连接失败';
    pill.className = 'mode offline';
    showOfflineChips(true);
  }
  updateBanner();
}
function updateBanner() {
  const previewish = !strict();
  // 旧实现靠 /healthz 的全局 demoReleaseUnverified 开关判断「演示 lane」。
  // 映射准入放开后该开关已作废（未核验名称不再被丢弃，而是逐条带 demoRelease 披露），
  // 若继续读它，横幅会在最需要披露时静默消失。改为从本次结果的候选反推。
  const unverified = state.sawUnverified === true;
  $('demo-banner').hidden = !(previewish || unverified || !state.health);
}
function showOfflineChips(on) {
  const box = $('offline-scenarios');
  box.hidden = !on;
  if (box.childElementCount) return;
  ['candidates', 'multiple', 'uncertain', 'no_snake', 'pending', 'timeout', 'invalid_output'].forEach((s) => {
    const b = el('button', 'chip', s);
    b.onclick = () => runOffline(s);
    box.append(b);
  });
}

async function runOffline(scenario) {
  const r = await fetch(`fixtures/${scenario}.json`);
  const body = await r.json();
  state.lastResult = { ...body, resultSource: 'mock' };
  $('sec-result').hidden = false;
  $('pending-box').hidden = true;
  $('result-meta').textContent = `离线模拟 · 场景 ${scenario} · 模拟结果与所选照片无关`;
  renderResult(state.lastResult);
}

$('photo').addEventListener('change', () => {
  const f = $('photo').files[0];
  $('btn-upload').disabled = !f;
  if (f) {
    state.photoMeta = { name: f.name, size: f.size };
    const url = URL.createObjectURL(f);
    $('thumb').src = url; $('thumb').hidden = false;
  }
});
$('consent').addEventListener('change', () => {
  $('btn-upload').disabled = !$('photo').files[0] || !$('consent').checked;
});

async function uploadBlob(blob, label) {
  const fd = new FormData();
  fd.append('image', blob, 'capture.jpg');
  fd.append('requestId', 'm-' + Date.now().toString(36));
  fd.append('uploadConsent', 'true');
  $('sec-result').hidden = false;
  $('result-body').replaceChildren();
  $('result-meta').textContent = '上传中…' + (label ? `（${label}）` : '');
  try {
    const r = await fetch(base() + '/v1/recognitions', {
      method: 'POST', headers: { Authorization: 'Bearer ' + token() }, body: fd,
    });
    const body = await r.json();
    if (r.status === 202) {
      state.recognitionId = body.recognitionId;
      $('pending-box').hidden = false;
      $('result-meta').textContent = '已受理（HTTP 202）· 等待一次手动查询';
    } else {
      $('pending-box').hidden = true;
      $('result-meta').textContent = `HTTP ${r.status}`;
      renderResult(body);
    }
  } catch (e) {
    $('result-meta').textContent = '请求失败：' + e.message;
  }
}

$('btn-upload').addEventListener('click', async () => {
  const f = $('photo').files[0];
  if (!f || !$('consent').checked || !base()) return;
  await uploadBlob(f, '手动选图');
});

let autoTimer = null, autoStream = null, autoBusy = false;
function stopAuto() {
  if (autoTimer) clearInterval(autoTimer);
  autoTimer = null;
  if (autoStream) autoStream.getTracks().forEach((t) => t.stop());
  autoStream = null;
  $('cam').hidden = true;
  $('btn-auto').hidden = false;
  $('btn-auto-stop').hidden = true;
}
$('btn-auto-stop').addEventListener('click', stopAuto);
$('btn-auto').addEventListener('click', async () => {
  if (!window.isSecureContext) {
    $('auto-note').textContent = '当前不是安全上下文，浏览器禁止调用相机。请用 USB 线连接电脑执行 adb reverse tcp:8765 tcp:8765 后，在手机打开 http://127.0.0.1:8765/m/ 再试；或改用手动选图。';
    return;
  }
  if (!base() || !$('consent').checked) {
    $('auto-note').textContent = '先填写代理地址与凭证，并勾选上传同意，再开启自动连拍。';
    return;
  }
  try {
    autoStream = await navigator.mediaDevices.getUserMedia({ video: { facingMode: 'environment' } });
  } catch (e) {
    $('auto-note').textContent = '相机不可用：' + e.message;
    return;
  }
  $('cam').srcObject = autoStream;
  $('cam').hidden = false;
  $('btn-auto').hidden = true;
  $('btn-auto-stop').hidden = false;
  const secs = Number($('auto-interval').value || 5);
  autoTimer = setInterval(async () => {
    if (autoBusy) return;
    autoBusy = true;
    try {
      const v = $('cam');
      const scale = Math.min(1, 1280 / Math.max(v.videoWidth, v.videoHeight));
      const cv = document.createElement('canvas');
      cv.width = Math.round(v.videoWidth * scale);
      cv.height = Math.round(v.videoHeight * scale);
      cv.getContext('2d').drawImage(v, 0, 0, cv.width, cv.height);
      const blob = await new Promise((res) => cv.toBlob(res, 'image/jpeg', 0.85));
      if (blob) await uploadBlob(blob, '自动连拍');
    } finally {
      autoBusy = false;
    }
  }, secs * 1000);
});

$('btn-refresh').addEventListener('click', async () => {
  if (!state.recognitionId) return;
  $('result-meta').textContent = '查询中…';
  try {
    const r = await fetch(base() + `/v1/recognitions/${state.recognitionId}/refresh`, {
      method: 'POST',
      headers: { Authorization: 'Bearer ' + token(), 'Content-Type': 'application/json' },
      body: JSON.stringify({ requestId: 'q-' + Date.now().toString(36) }),
    });
    const body = await r.json();
    state.lastResult = body;
    $('pending-box').hidden = true;
    $('result-meta').textContent = `resultSource=${body.resultSource || '?'} · status=${body.status}`;
    renderResult(body);
  } catch (e) {
    $('result-meta').textContent = '查询失败：' + e.message;
  }
});

function cardFor(speciesId) {
  if (!state.bundle) return null;
  return (strict() ? state.bundle.normal : state.bundle.preview)[speciesId] || null;
}

function renderResult(body) {
  const box = $('result-body'); box.replaceChildren();
  $('sec-record').hidden = false;
  $('sec-hospital').hidden = false;
  updateBanner();
  if (body.error) {
    box.append(el('p', 'warn', `${body.error.code}：${body.error.message}`));
    return;
  }
  const cands = body.candidates || [];
  // 本次结果里只要有「名称映射未经人工核验」的候选，就点亮顶部披露横幅。
  state.sawUnverified = cands.some((c) => c.demoRelease === true);
  updateBanner();
  if (!cands.length) {
    box.append(el('p', 'warn', body.status === 'no_snake'
      ? '未检测到蛇。这不表示现场安全。'
      : '这次没有可放行的候选：按未知处理，不提供“安全”结论。'));
    box.append(el('p', 'hint', '请保留原图；记录与求助入口不受影响。'));
    return;
  }
  cands.forEach((c) => {
    const wrap = el('div', 'cand');
    const card = cardFor(c.speciesId);
    wrap.append(el('h3', '', c.commonName || '未知'));
    if (c.scientificName) wrap.append(el('div', 'sci', c.scientificName));
    if (c.demoRelease) wrap.append(el('div', 'hint', '技术标注：demoRelease（演示放行），详见页面顶部横幅'));
    if (!card) {
      wrap.append(el('p', 'warn', '该候选未入库：按「宁缺勿错」不展示物种信息；通用安全提醒与求助入口仍可用。请保留原图并按未知处理。'));
      box.append(wrap);
      return;
    }
    if (card.aliases && card.aliases.length) {
      wrap.append(el('p', 'hint', '别名（地域参考）：' + card.aliases
        .map((a) => a.alias + (a.region ? `（${a.region}）` : '') + (a.level === 'genus' ? '［属级］' : '')).join('　/　')));
    }
    if (card.hook) {
      wrap.append(el('h4', 'hook', card.hook));
      const ul = el('ul', 'checklist');
      (card.checklist || []).forEach((t) => ul.append(el('li', '', t)));
      wrap.append(ul);
    } else {
      const st = el('ul', 'status-list');
      st.append(el('li', '', '比对文案：' + (card.contentStatus === 'unavailable' ? '未整理' : '待审核') + '（正常模式不展示；预览模式带目标态标注展示）'));
      st.append(el('li', '', '参考图：可发布 ' + card.images.length + ' 张，来源门禁拦截 ' + card.hiddenImageCount + ' 张'));
      st.append(el('li', '', '以上为审核进度，不是安全结论；安全提醒与求助入口不受影响。'));
      wrap.append(st);
    }
    (card.images || []).forEach((img) => {
      const fig = el('figure', 'ref');
      const im = el('img'); im.src = img.file; im.alt = img.role || '';
      fig.append(im, el('figcaption', '', `${img.role || ''}｜${img.rights}`));
      wrap.append(fig);
    });
    if ((card.externalRefs || []).length) {
      const p = el('p', 'links');
      card.externalRefs.forEach((ref) => {
        const a = el('a', '', ref.name); a.href = ref.url; a.target = '_blank'; a.rel = 'noopener';
        p.append(a, ' ');
      });
      p.append(el('span', 'hint', '（仅供参考）'));
      wrap.append(p);
    }
    box.append(wrap);
  });
}

$('btn-save').addEventListener('click', () => {
  const rec = {
    at: new Date().toISOString(),
    bitten: $('rec-bitten').value,
    part: $('rec-part').value,
    note: $('rec-note').value,
    photo: state.photoMeta || null,
    recognitionId: state.recognitionId || null,
    status: state.lastResult?.status || null,
    candidates: (state.lastResult?.candidates || []).map((c) => c.speciesId),
  };
  const all = JSON.parse(localStorage.getItem('ypzs-records') || '[]');
  all.push(rec);
  localStorage.setItem('ypzs-records', JSON.stringify(all));
  renderRecords();
});
function renderRecords() {
  const ul = $('rec-list'); ul.replaceChildren();
  JSON.parse(localStorage.getItem('ypzs-records') || '[]').slice().reverse().forEach((r) => {
    ul.append(el('li', '', `${r.at.slice(0, 16).replace('T', ' ')} ｜ 被咬:${r.bitten === 'yes' ? '是' : '否'} ｜ ${r.part} ｜ 候选:${(r.candidates || []).join(',') || '无'}`));
  });
}
$('btn-copy').addEventListener('click', () => {
  const all = JSON.parse(localStorage.getItem('ypzs-records') || '[]');
  const lines = all.map((r) => `${r.at} 被咬=${r.bitten} 部位=${r.part} 描述=${r.note || '-'} 候选=${(r.candidates || []).join(',') || '无'}`);
  navigator.clipboard?.writeText(lines.join('\n') || '暂无记录');
});

$('btn-connect').addEventListener('click', connect);
renderRecords();
loadBundle().then(connect);
