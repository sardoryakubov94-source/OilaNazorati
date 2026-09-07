import { getApps, getApp, initializeApp } from 'https://www.gstatic.com/firebasejs/10.12.2/firebase-app.js';
import { getAuth } from 'https://www.gstatic.com/firebasejs/10.12.2/firebase-auth.js';
import { getFirestore, doc, getDoc, setDoc, updateDoc, collection, getDocs, query, where, limit, onSnapshot, arrayUnion, deleteField } from 'https://www.gstatic.com/firebasejs/10.12.2/firebase-firestore.js';

const config = {
  apiKey: 'AIzaSyB72y9YkdCifEodbo2g_43FbbcDbvg0Q4',
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
let peer = null;
let remoteAudio = null;
let fallbackTimer = null;
let usingLegacy = false;
let currentTarget = null;
let currentRequestId = null;
let parentCandidateKeys = new Set();
let childCandidateKeys = new Set();

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

function iceServers() {
  return [
    { urls: 'stun:stun.l.google.com:19302' },
    { urls: 'stun:stun1.l.google.com:19302' }
  ];
}

async function openAudio() {
  if (starting) return;
  starting = true;
  usingLegacy = false;
  setStatus('⏳ Jonli ulanish tayyorlanmoqda…');
  startBtn.disabled = true;
  try {
    const user = auth.currentUser;
    if (!user) throw new Error('Avval ota-ona paneliga kiring.');
    currentTarget = await selectedChild();
    if (!currentTarget) throw new Error('Farzand qurilmasi aniqlanmadi.');
    const parent = await getDoc(doc(db, 'parents', user.uid));
    if (!parent.exists()) throw new Error('Ota-ona profili topilmadi.');

    const r = refs(currentTarget);
    currentRequestId = `mic_${Date.now()}_${Math.random().toString(36).slice(2, 8)}`;
    parentCandidateKeys = new Set();
    childCandidateKeys = new Set();
    seen = new Set();
    currentSession = null;

    requestUnsub = onSnapshot(r.request, async snap => {
      const d = snap.data() || {};
      if (d.requestId !== currentRequestId) return;
      if (d.status === 'webrtc_requested' || d.status === 'requested') {
        if (!usingLegacy) setStatus('⏳ Bola qurilmasidan kutilmoqda…');
      }
      if (d.status === 'active' && d.transport === 'webrtc' && d.webrtcAnswer && peer && !peer.currentRemoteDescription) {
        try {
          await peer.setRemoteDescription({ type: 'answer', sdp: d.webrtcAnswer });
          setStatus('🔴 Jonli ovoz');
        } catch (_) { fallbackToLegacy('WebRTC javobi qabul qilinmadi'); }
      }
      if (d.status === 'stopped') { setStatus('To‘xtatildi'); await cleanupAudio(true); }
      if (d.status === 'failed') {
        if (!usingLegacy && d.transport === 'webrtc') fallbackToLegacy(d.error || 'WebRTC ulanishi muvaffaqiyatsiz');
        else { setStatus(`❌ ${d.error || 'Mikrofon ulanmagan'}`); await cleanupAudio(true); }
      }
      if (peer && d.childCandidates) await applyChildCandidates(d.childCandidates);
    }, e => setStatus(`❌ ${e.message || 'Ulanish xatosi'}`));

    await startWebRtc(r);
  } catch (e) {
    setStatus(`❌ ${e.message || 'Xato'}`);
    startBtn.disabled = false;
    await cleanupPeer();
  } finally { starting = false; }
}

async function startWebRtc(r) {
  clearTimeout(fallbackTimer);
  peer = new RTCPeerConnection({ iceServers: iceServers() });
  remoteAudio = document.createElement('audio');
  remoteAudio.autoplay = true;
  remoteAudio.playsInline = true;
  remoteAudio.controls = false;
  remoteAudio.setAttribute('aria-label', 'Jonli ovoz');
  remoteAudio.style.display = 'none';
  document.body.appendChild(remoteAudio);

  peer.ontrack = async event => {
    const stream = event.streams?.[0] || new MediaStream([event.track]);
    remoteAudio.srcObject = stream;
    try { await remoteAudio.play(); } catch (_) { setStatus('🔴 Jonli ovoz — eshitish uchun oynaga bosing'); }
  };
  peer.onicecandidate = async event => {
    if (!event.candidate || !currentTarget || !currentRequestId) return;
    const c = event.candidate;
    const key = c.candidate;
    if (parentCandidateKeys.has(key)) return;
    parentCandidateKeys.add(key);
    try {
      await updateDoc(r.request, {
        parentCandidates: arrayUnion({ candidate: c.candidate, sdpMid: c.sdpMid, sdpMLineIndex: c.sdpMLineIndex })
      });
    } catch (_) {}
  };
  peer.onconnectionstatechange = () => {
    const state = peer?.connectionState;
    if (state === 'connected') {
      clearTimeout(fallbackTimer);
      setStatus('🔴 Jonli ovoz');
    } else if (state === 'failed' || state === 'closed') {
      fallbackToLegacy('WebRTC tarmoq ulanishi ishlamadi');
    }
  };
  peer.addTransceiver('audio', { direction: 'recvonly' });

  await setDoc(r.request, {
    requestId: currentRequestId,
    status: 'webrtc_requested',
    transport: 'webrtc',
    requestedByUid: auth.currentUser.uid,
    requestedAt: Date.now(),
    updatedAt: Date.now(),
    webrtcOffer: deleteField(),
    webrtcAnswer: deleteField(),
    parentCandidates: [],
    childCandidates: []
  }, { merge: true });

  const offer = await peer.createOffer();
  await peer.setLocalDescription(offer);
  await updateDoc(r.request, {
    webrtcOffer: offer.sdp,
    updatedAt: Date.now()
  });

  // If P2P negotiation cannot connect, switch automatically to the existing
  // Firestore PCM path so the current voice feature is not lost.
  fallbackTimer = setTimeout(() => {
    if (peer && peer.connectionState !== 'connected') fallbackToLegacy('WebRTC ulanishi uzoq davom etdi');
  }, 9000);
}

async function applyChildCandidates(list) {
  if (!peer) return;
  for (const raw of list || []) {
    const c = raw || {};
    if (!c.candidate || childCandidateKeys.has(c.candidate)) continue;
    childCandidateKeys.add(c.candidate);
    try { await peer.addIceCandidate(new RTCIceCandidate(c)); } catch (_) {}
  }
}

async function fallbackToLegacy(reason) {
  if (usingLegacy || !currentTarget || !currentRequestId) return;
  usingLegacy = true;
  clearTimeout(fallbackTimer);
  setStatus('⏳ WebRTC ulanmagan — zaxira ovoz kanali ishga tushmoqda…');
  await cleanupPeer();
  const r = refs(currentTarget);
  try {
    await updateDoc(r.request, {
      status: 'requested',
      transport: 'legacy',
      updatedAt: Date.now(),
      webrtcOffer: deleteField(),
      webrtcAnswer: deleteField(),
      parentCandidates: [],
      childCandidates: []
    });
    attachLegacyAudio(r.audio, currentRequestId);
    setStatus('⏳ Bola qurilmasidan kutilmoqda…');
  } catch (_) {
    setStatus(`❌ ${reason || 'Ovoz ulanmagan'}`);
    startBtn.disabled = false;
  }
}

function attachLegacyAudio(audioCollection, requestId) {
  audioUnsub?.();
  audioUnsub = onSnapshot(query(audioCollection, where('sessionId', '==', currentSession)), snap => {
    snap.docChanges().forEach(change => {
      if (change.type !== 'added') return;
      const data = change.doc.data();
      const seq = Number(data.sequence ?? -1);
      if (seq < 0 || seen.has(seq)) return;
      seen.add(seq);
      const bytes = data.audio?.toUint8Array?.();
      if (!bytes) return;
      ensureAudioContext();
      playPcm(bytes, audioContext);
    });
  }, () => setStatus('❌ Zaxira ovoz oqimi xatosi'));
}

function ensureAudioContext() {
  if (!audioContext) audioContext = new (window.AudioContext || window.webkitAudioContext)();
  audioContext.resume().catch(() => {});
  if (!nextPlayTime) nextPlayTime = audioContext.currentTime + 0.08;
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
    const target = currentTarget || await selectedChild();
    if (target) await updateDoc(refs(target).request, { status: 'stop_requested', updatedAt: Date.now() });
  } catch (_) {}
  setStatus('⏳ To‘xtatilmoqda…');
  stopBtn.disabled = true;
  await cleanupAudio(true);
}

async function cleanupPeer() {
  clearTimeout(fallbackTimer);
  fallbackTimer = null;
  try { peer?.close(); } catch (_) {}
  peer = null;
  if (remoteAudio) { remoteAudio.srcObject = null; remoteAudio.remove(); remoteAudio = null; }
}

async function cleanupAudio(closeContext) {
  requestUnsub?.(); requestUnsub = null;
  audioUnsub?.(); audioUnsub = null;
  await cleanupPeer();
  currentSession = null;
  currentTarget = null;
  currentRequestId = null;
  seen.clear();
  parentCandidateKeys.clear();
  childCandidateKeys.clear();
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
