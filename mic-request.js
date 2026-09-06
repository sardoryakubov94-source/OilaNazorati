import { getApps, getApp, initializeApp } from 'https://www.gstatic.com/firebasejs/10.12.2/firebase-app.js';
import { getAuth } from 'https://www.gstatic.com/firebasejs/10.12.2/firebase-auth.js';
import { getFirestore, doc, getDoc, setDoc, updateDoc, collection, getDocs, query, where, limit, onSnapshot } from 'https://www.gstatic.com/firebasejs/10.12.2/firebase-firestore.js';

const config = {
  apiKey: 'AIzaSyB72y9YkdCifEodbo2g_43FbbcDbvg0QF4',
  authDomain: 'oilanazorat-3c8de.firebaseapp.com',
  projectId: 'oilanazorat-3c8de',
  storageBucket: 'oilanazorat-3c8de.firebasestorage.app',
  messagingSenderId: '1086556994078',
  appId: '1:1086556994078:web:5e576984e784d0bbdc4c0f',
  measurementId: 'G-NWER8TE97X'
};
const app = getApps().length ? getApp() : initializeApp(config);
const auth = getAuth(app);
const db = getFirestore(app);

let card = null;
let modal = null;
let statusEl = null;
let startBtn = null;
let stopBtn = null;
let requestUnsub = null;
let audioUnsub = null;
let audioContext = null;
let nextPlayTime = 0;
let seen = new Set();
let currentSession = null;
let starting = false;
let voiceTab = null;

function familyCode() { return localStorage.getItem('family'); }
async function selectedChild() {
  const family = familyCode();
  const name = document.getElementById('childName')?.textContent?.trim();
  if (!family || !name) return null;
  const q = query(collection(db, 'families', family, 'children'), where('nomi', '==', name), limit(1));
  const snap = await getDocs(q);
  if (snap.empty) return null;
  return { family, id: snap.docs[0].id };
}
function refs(target) {
  const base = ['families', target.family, 'children', target.id];
  return { request: doc(db, ...base, 'mic_requests', 'current'), audio: collection(db, ...base, 'mic_audio') };
}
function setStatus(text) { if (statusEl) statusEl.textContent = text; }

async function openAudio() {
  if (starting) return;
  starting = true;
  setStatus('⏳ Ulanmoqda…');
  startBtn.disabled = true;
  try {
    const user = auth.currentUser;
    if (!user) throw new Error('Avval ota-ona paneliga kiring.');
    const target = await selectedChild();
    if (!target) throw new Error('Farzand qurilmasi aniqlanmadi.');
    const parent = await getDoc(doc(db, 'parents', user.uid));
    if (!parent.exists()) throw new Error('Ota-ona profili topilmadi.');

    audioContext = new (window.AudioContext || window.webkitAudioContext)();
    await audioContext.resume();
    nextPlayTime = audioContext.currentTime + 0.08;
    seen = new Set();
    currentSession = null;
    const r = refs(target);
    const requestId = `mic_${Date.now()}_${Math.random().toString(36).slice(2, 8)}`;
    await setDoc(r.request, { requestId, status: 'requested', requestedByUid: user.uid, requestedAt: Date.now(), updatedAt: Date.now() });
    requestUnsub = onSnapshot(r.request, snap => {
      const d = snap.data() || {};
      if (d.requestId !== requestId) return;
      if (d.status === 'requested') setStatus('⏳ Bola qurilmasidan kutilmoqda…');
      if (d.status === 'active') {
        setStatus('🔴 Jonli ovoz');
        currentSession = d.sessionId || null;
        if (currentSession) attachAudio(r.audio, currentSession);
      }
      if (d.status === 'stopped') { setStatus('To‘xtatildi'); cleanupAudio(true); }
      if (d.status === 'failed') { setStatus('❌ Mikrofon ulanmagan'); cleanupAudio(true); }
    }, e => setStatus(`❌ ${e.message || 'Ulanish xatosi'}`));
    stopBtn.disabled = false;
  } catch (e) {
    setStatus(`❌ ${e.message || 'Xato'}`);
    startBtn.disabled = false;
    if (audioContext) { await audioContext.close().catch(() => {}); audioContext = null; }
  } finally { starting = false; }
}

function attachAudio(audioCollection, sessionId) {
  if (currentSession !== sessionId || audioUnsub) return;
  audioUnsub = onSnapshot(query(audioCollection, where('sessionId', '==', sessionId)), snap => {
    snap.docChanges().forEach(change => {
      if (change.type !== 'added') return;
      const data = change.doc.data();
      const seq = Number(data.sequence ?? -1);
      if (seq < 0 || seen.has(seq)) return;
      seen.add(seq);
      const bytes = data.audio?.toUint8Array?.();
      if (!bytes || !audioContext) return;
      playPcm(bytes, audioContext);
    });
  }, () => setStatus('❌ Ovoz oqimi xatosi'));
}

function playPcm(bytes, ctx) {
  const samples = new Int16Array(bytes.buffer, bytes.byteOffset, Math.floor(bytes.byteLength / 2));
  const buffer = ctx.createBuffer(1, samples.length, 16000);
  const channel = buffer.getChannelData(0);
  for (let i = 0; i < samples.length; i++) channel[i] = samples[i] / 32768;
  const source = ctx.createBufferSource();
  source.buffer = buffer;
  source.connect(ctx.destination);
  const when = Math.max(ctx.currentTime + 0.01, nextPlayTime);
  source.start(when);
  nextPlayTime = when + buffer.duration;
}

async function stopAudio() {
  if (starting) return;
  try {
    const target = await selectedChild();
    if (target) await updateDoc(refs(target).request, { status: 'stop_requested', updatedAt: Date.now() });
  } catch (_) {}
  setStatus('⏳ To‘xtatilmoqda…');
  stopBtn.disabled = true;
  await cleanupAudio(true);
}

async function cleanupAudio(closeContext) {
  requestUnsub?.(); requestUnsub = null;
  audioUnsub?.(); audioUnsub = null;
  currentSession = null;
  seen.clear();
  nextPlayTime = 0;
  if (closeContext && audioContext) { await audioContext.close().catch(() => {}); audioContext = null; }
  startBtn.disabled = false;
  stopBtn.disabled = true;
}

function buildUi() {
  const dashboard = document.getElementById('dashboard');
  const tabs = document.getElementById('tabs');
  if (!dashboard || !tabs) return;

  const screenshotTab = tabs.querySelector('[data-tab="ss"]');
  if (!screenshotTab) return;

  if (!voiceTab?.isConnected) {
    voiceTab = document.createElement('button');
    voiceTab.type = 'button';
    voiceTab.dataset.tab = 'voice';
    voiceTab.innerHTML = '<span aria-hidden="true" style="font-size:24px;line-height:24px">🎙️</span><br>Ovoz';
    voiceTab.title = 'Jonli eshitish';
    voiceTab.onclick = () => { modal?.classList.add('active'); setStatus('Tayyor'); };
    tabs.insertBefore(voiceTab, screenshotTab.nextSibling);
  } else if (voiceTab.previousElementSibling !== screenshotTab) {
    tabs.insertBefore(voiceTab, screenshotTab.nextSibling);
  }

  if (!document.getElementById('voiceTabStyle')) {
    const style = document.createElement('style');
    style.id = 'voiceTabStyle';
    style.textContent = '#tabs{grid-template-columns:repeat(5,1fr)}#tabs [data-tab="voice"]{border-color:#dce2eb;background:#fff;color:#596578}#tabs [data-tab="voice"]:active{transform:scale(.97)}@media(max-width:430px){#tabs{grid-template-columns:repeat(3,1fr)}}';
    document.head.appendChild(style);
  }

  if (card?.isConnected) card.remove();
  card = null;

  if (!modal?.isConnected) {
    modal = document.createElement('div');
    modal.className = 'modal';
    modal.innerHTML = '<div class="sheet"><h2 style="margin:0 0 8px">🎙️ Ovoz</h2><p id="micStatus" class="muted" style="margin:0 0 14px">Tayyor</p><button id="micStart">▶️ Eshitishni boshlash</button><button id="micStop" style="margin-top:8px;background:#dc2626" disabled>⏹️ To‘xtatish</button><button id="micClose" style="margin-top:8px;background:#eef2ff;color:#111827">Yopish</button></div>';
    document.body.appendChild(modal);
    statusEl = modal.querySelector('#micStatus');
    startBtn = modal.querySelector('#micStart');
    stopBtn = modal.querySelector('#micStop');
    startBtn.onclick = openAudio;
    stopBtn.onclick = stopAudio;
    modal.querySelector('#micClose').onclick = () => { if (!stopBtn.disabled) stopAudio(); modal.classList.remove('active'); };
  }
}

const observer = new MutationObserver(buildUi);
observer.observe(document.body, { childList: true, subtree: true, attributes: true, attributeFilter: ['class'] });
setInterval(buildUi, 800);
setTimeout(buildUi, 600);
