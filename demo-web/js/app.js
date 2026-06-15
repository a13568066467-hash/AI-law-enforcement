/**
 * AI Field Cam Demo — 与固件 FSM 行为对齐的交互模拟
 * 录像 | 拍照识图 | AI 助手（双击 AI 开/关）
 */

const State = {
  IDLE: 'idle',
  AI: 'ai',
  CAPTURE: 'capture',
  RECORD: 'record',
};

const MOCK_RECOGNITION = [
  { type: 'explain', text: '铭牌读数：型号 ABC-200，额定电压 380V，出厂日期 2024-03。' },
  { type: 'explain', text: '这是一台立式空调外机，表面有轻微锈蚀，建议检查支架固定。' },
  { type: 'explain', text: '仪表显示：压力 1.2 MPa，温度 46℃，处于正常工况范围。' },
  { type: 'explain', text: '现场为混凝土浇筑区域，可见钢筋绑扎完成，养护膜未覆盖。' },
];

const TIMING = {
  POWER_LONG_MS: 3000,
  AI_DOUBLE_MS: 600,
  LOW_BAT_ALERT_PERCENT: 15,
  LOW_BAT_RECORD_MIN_PERCENT: 10,
  LOW_BAT_ALERT_INTERVAL_MS: 120000,
};

const app = {
  state: State.IDLE,
  powered: true,
  battery: 78,
  recordStart: null,
  recordTimer: null,
  photos: [],
  videos: [],
  lastRecognition: null,
  aiLastRelease: 0,
  aiClickCount: 0,
  powerPressStart: null,
  shutterPressStart: null,
  mockIdx: 0,
  lastLowBatAlert: 0,
};

const el = {
  statusState: document.getElementById('statusState'),
  statusRecord: document.getElementById('statusRecord'),
  statusBattery: document.getElementById('statusBattery'),
  ttsText: document.getElementById('ttsText'),
  chatLog: document.getElementById('chatLog'),
  voiceInput: document.getElementById('voiceInput'),
  photoList: document.getElementById('photoList'),
  videoList: document.getElementById('videoList'),
  ledBlue: document.getElementById('ledBlue'),
  ledRed: document.getElementById('ledRed'),
  ledWhite: document.getElementById('ledWhite'),
};

function stateLabel(s) {
  const map = {
    [State.IDLE]: '空闲',
    [State.AI]: 'AI 聆听',
    [State.CAPTURE]: '拍照识图',
    [State.RECORD]: '录像中',
  };
  return map[s] || s;
}

function setState(s) {
  app.state = s;
  el.statusState.textContent = stateLabel(s);
  updateLeds();
}

function updateLeds() {
  el.ledBlue.classList.toggle('on-blue', app.state === State.AI);
  el.ledRed.classList.toggle('on-red', app.state === State.RECORD);
}

function speak(text, duration = 2000) {
  el.ttsText.textContent = text;
  return new Promise((r) => setTimeout(r, duration));
}

function addChat(role, text) {
  const div = document.createElement('div');
  div.className = `chat-msg ${role}`;
  div.textContent = text;
  el.chatLog.appendChild(div);
  el.chatLog.scrollTop = el.chatLog.scrollHeight;
}

function formatTime(d = new Date()) {
  return d.toLocaleTimeString('zh-CN', { hour: '2-digit', minute: '2-digit', second: '2-digit' });
}

function formatDuration(ms) {
  const s = Math.floor(ms / 1000);
  const m = Math.floor(s / 60);
  return `${m}:${String(s % 60).padStart(2, '0')}`;
}

function checkLowBatteryAlert() {
  if (!app.powered || app.battery > TIMING.LOW_BAT_ALERT_PERCENT) {
    return;
  }
  const now = Date.now();
  if (now - app.lastLowBatAlert < TIMING.LOW_BAT_ALERT_INTERVAL_MS) {
    return;
  }
  app.lastLowBatAlert = now;
  addChat('system', `⚠ 电量 ${app.battery}%`);
  speak(`电量不足百分之${app.battery}，请尽快充电`, 2800);
}

function forceIdle() {
  if (app.state === State.RECORD) {
    clearInterval(app.recordTimer);
    app.recordStart = null;
    el.statusRecord.textContent = '—';
  }
  setState(State.IDLE);
}

async function startRecording() {
  if (!app.powered) return speak('请先开机');
  if (app.state === State.RECORD) return speak('已在录像中');
  if (app.battery <= TIMING.LOW_BAT_RECORD_MIN_PERCENT) {
    return speak('电量过低，无法录像');
  }
  checkLowBatteryAlert();
  if (app.state === State.CAPTURE) return speak('正在拍照，请稍候');
  if (app.state === State.AI) setState(State.IDLE);

  setState(State.RECORD);
  app.recordStart = Date.now();
  el.statusRecord.textContent = '00:00';
  addChat('system', '● 开始录像');
  await speak('开始录像', 800);

  app.recordTimer = setInterval(() => {
    if (app.state !== State.RECORD) return;
    el.statusRecord.textContent = formatDuration(Date.now() - app.recordStart);
  }, 1000);
}

async function stopRecording() {
  if (app.state !== State.RECORD) return speak('当前没有在录像');

  clearInterval(app.recordTimer);
  const duration = Date.now() - app.recordStart;
  const video = {
    id: Date.now(),
    time: formatTime(),
    duration: formatDuration(duration),
    durationMs: duration,
    resolution: '720p @ 15fps',
    format: 'MJPEG',
  };
  app.videos.unshift(video);
  renderVideos();

  setState(State.IDLE);
  app.recordStart = null;
  el.statusRecord.textContent = '—';
  addChat('system', `■ 录像已保存 (${video.duration})`);
  await speak('录像已保存', 1000);
}

async function captureAndRecognize(source = '按键') {
  if (!app.powered) return speak('请先开机');
  if (app.state === State.RECORD) {
    await speak('录像中，请长按快门停止录像');
    return;
  }
  if (app.state === State.CAPTURE) return;

  const prev = app.state;
  setState(State.CAPTURE);
  el.ledWhite.classList.add('on-white', 'flash');
  addChat('system', `📷 拍照 (${source})`);
  await speak('咔嚓', 400);
  el.ledWhite.classList.remove('on-white', 'flash');

  await speak('正在识别…', 1200);

  const mock = MOCK_RECOGNITION[app.mockIdx % MOCK_RECOGNITION.length];
  app.mockIdx++;
  app.lastRecognition = mock;

  const photo = {
    id: Date.now(),
    time: formatTime(),
    type: mock.type,
    result: mock.text,
  };
  app.photos.unshift(photo);
  renderPhotos();

  addChat('ai', `[识图] ${mock.text}`);
  setState(prev === State.AI ? State.AI : State.IDLE);
  await speak(mock.text, 2500);
}

async function toggleAI() {
  if (!app.powered) return speak('请先开机');
  if (app.battery <= TIMING.LOW_BAT_RECORD_MIN_PERCENT) {
    await speak(`电量过低，无法使用 AI 助手`);
    return;
  }
  checkLowBatteryAlert();
  if (app.state === State.RECORD) {
    await speak('请先停止录像，或长按快门 1 秒停止');
    return;
  }
  if (app.state === State.CAPTURE) {
    await speak('正在拍照，请稍候');
    return;
  }
  if (app.state === State.AI) {
    setState(State.IDLE);
    addChat('system', 'AI 助手已关闭');
    await speak('好的', 600);
    return;
  }
  setState(State.AI);
  addChat('ai', '我在，请说。');
  await speak('我在', 800);
}

function onAIRelease() {
  const now = Date.now();
  const gap = app.aiLastRelease ? now - app.aiLastRelease : 9999;
  if (gap > TIMING.AI_DOUBLE_MS) {
    app.aiClickCount = 0;
  }
  app.aiClickCount++;
  app.aiLastRelease = now;
  if (app.aiClickCount >= 2) {
    app.aiClickCount = 0;
    app.aiLastRelease = 0;
    toggleAI();
  }
}

async function handleVoiceInput(text) {
  if (!text.trim()) return;
  if (!app.powered) {
    await speak('请先开机');
    return;
  }
  addChat('user', text);
  el.voiceInput.value = '';

  const t = text.trim();

  if (/停止录像|结束录像|停录/.test(t)) {
    await stopRecording();
    addChat('ai', '好的，录像已停止。');
    return;
  }
  if (/开始录像/.test(t) && !/停止|结束/.test(t)) {
    await startRecording();
    addChat('ai', '好的，开始录像。');
    return;
  }
  if (/识别|拍一张|拍照|读一下|这是什么/.test(t)) {
    await captureAndRecognize('语音');
    return;
  }
  if (/再见|退出助手|关闭助手/.test(t)) {
    forceIdle();
    addChat('ai', '好的，有需要再叫我。');
    await speak('好的', 600);
    return;
  }
  if (app.lastRecognition && /刚才|上一张|那个|多少|型号|参数/.test(t)) {
    if (app.state !== State.AI) setState(State.AI);
    const reply = `根据上次识图：${app.lastRecognition.text.slice(0, 60)}…`;
    addChat('ai', reply);
    await speak(reply, 2200);
    return;
  }

  if (app.state !== State.AI) setState(State.AI);
  const reply = '我是 AI 实地助手。你可以说：开始录像、识别一下；双击 AI 键可关闭助手。';
  addChat('ai', reply);
  await speak(reply, 2200);
}

async function powerOn() {
  app.powered = true;
  addChat('system', '相机已开机');
  await speak('已开机', 600);
  setState(State.IDLE);
}

async function powerOff() {
  if (app.state === State.RECORD) await stopRecording();
  forceIdle();
  app.powered = false;
  addChat('system', '相机已关机');
  await speak('已关机', 600);
}

function renderPhotos() {
  el.photoList.innerHTML = app.photos.length
    ? app.photos
        .map(
          (p) => `
    <li class="media-item">
      <div class="thumb">📷</div>
      <div class="meta">${p.time} · 识图解释</div>
      <div class="result">${p.result}</div>
    </li>`
        )
        .join('')
    : '<li class="media-item"><div class="result" style="color:var(--muted)">暂无照片，单击快门拍照</div></li>';
}

function renderVideos() {
  el.videoList.innerHTML = app.videos.length
    ? app.videos
        .map(
          (v) => `
    <li class="media-item">
      <div class="thumb">🎬</div>
      <div class="meta">${v.time} · ${v.duration} · ${v.resolution}</div>
      <div class="result">${v.format} · 已同步到 App</div>
    </li>`
        )
        .join('')
    : '<li class="media-item"><div class="result" style="color:var(--muted)">暂无录像，说「开始录像」</div></li>';
}

document.getElementById('btnShutter').addEventListener('mousedown', () => {
  if (!app.powered) return;
  app.shutterPressStart = Date.now();
});

document.getElementById('btnShutter').addEventListener('mouseup', async () => {
  if (!app.powered) {
    await speak('请先开机');
    return;
  }
  const held = Date.now() - (app.shutterPressStart || 0);
  app.shutterPressStart = null;
  if (app.state === State.RECORD && held >= 1000) {
    await stopRecording();
  } else if (app.state !== State.RECORD) {
    await captureAndRecognize('单击快门');
  }
});

document.getElementById('btnPower').addEventListener('mousedown', () => {
  app.powerPressStart = Date.now();
});

document.getElementById('btnPower').addEventListener('mouseup', () => {
  const held = Date.now() - (app.powerPressStart || 0);
  app.powerPressStart = null;
  if (held >= TIMING.POWER_LONG_MS) {
    if (app.powered) powerOff();
    else powerOn();
  }
});

document.getElementById('btnAI').addEventListener('mouseup', () => {
  if (!app.powered) return;
  onAIRelease();
});

document.getElementById('btnSend').addEventListener('click', () => {
  handleVoiceInput(el.voiceInput.value);
});

el.voiceInput.addEventListener('keydown', (e) => {
  if (e.key === 'Enter') handleVoiceInput(el.voiceInput.value);
});

document.querySelectorAll('.chip').forEach((btn) => {
  btn.addEventListener('click', () => handleVoiceInput(btn.dataset.cmd));
});

document.querySelectorAll('.tab').forEach((tab) => {
  tab.addEventListener('click', () => {
    document.querySelectorAll('.tab').forEach((t) => t.classList.remove('active'));
    document.querySelectorAll('.tab-content').forEach((c) => c.classList.remove('active'));
    tab.classList.add('active');
    document.getElementById(tab.dataset.tab === 'photos' ? 'tabPhotos' : 'tabVideos').classList.add('active');
  });
});

addChat('system', 'Demo — 先 ≤15% 提醒，≤10% 禁录像/AI；双击电量切换 8%/78%');
renderPhotos();
renderVideos();

setInterval(() => {
  if (app.battery > TIMING.LOW_BAT_ALERT_PERCENT + 2) {
    app.lastLowBatAlert = 0;
  }
  if (app.battery <= TIMING.LOW_BAT_RECORD_MIN_PERCENT && app.state === State.RECORD) {
    forceIdle();
    addChat('system', '⚠ 电量过低，已停止录像');
  }
  checkLowBatteryAlert();
}, 5000);

document.getElementById('statusBattery').addEventListener('dblclick', () => {
  app.battery = app.battery > 20 ? 8 : 78;
  el.statusBattery.textContent = `${app.battery}%`;
  checkLowBatteryAlert();
});
