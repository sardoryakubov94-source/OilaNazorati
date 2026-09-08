import { initializeApp } from 'https://www.gstatic.com/firebasejs/10.12.2/firebase-app.js';
import { getFirestore, doc, onSnapshot } from 'https://www.gstatic.com/firebasejs/10.12.2/firebase-firestore.js';

const SIM_FIREBASE_CONFIG = {
  apiKey: 'AIzaSyB72y9YkdCifEodbo2g_43FbbcDbvg0QF4',
  authDomain: 'oilanazorat-3c8de.firebaseapp.com',
  projectId: 'oilanazorat-3c8de',
  storageBucket: 'oilanazorat-3c8de.firebasestorage.app',
  messagingSenderId: '1086556994078',
  appId: '1:1086556994078:web:5e576984e784d0bbdc4c0f'
};

const simApp = initializeApp(SIM_FIREBASE_CONFIG, 'sim-panel');
const simDb = getFirestore(simApp);
let selectedChildId = null;
let selectedFamily = null;
let simUnsub = null;

const esc = value => String(value ?? '').replace(/[&<>\"']/g, c => ({
  '&':'&amp;', '<':'&lt;', '>':'&gt;', '\"':'&quot;', "'":'&#39;'
}[c]));

function familyCode() {
  return localStorage.getItem('family');
}

function findSelectedChildId() {
  const name = document.getElementById('childName')?.textContent?.trim();
  if (!name) return null;
  const buttons = document.querySelectorAll('#childList .child[data-child-id]');
  for (const button of buttons) {
    const b = button.querySelector('b');
    if (b?.textContent?.trim() === name) return button.dataset.childId;
  }
  return null;
}

function ensureCard() {
  const content = document.getElementById('content');
  if (!content) return null;
  let card = document.getElementById('simPanelCard');
  if (!card) {
    card = document.createElement('div');
    card.id = 'simPanelCard';
    card.style.cssText = 'background:#fff;border:1px solid #e1e6ee;border-radius:18px;padding:16px;margin-bottom:12px;box-shadow:0 6px 18px #1018280a';
    content.prepend(card);
  }
  return card;
}

function render(data) {
  const card = ensureCard();
  if (!card) return;

  const sims = Array.isArray(data?.simCards) ? data.simCards : [];
  const count = Number(data?.simCount ?? sims.length);
  const updated = Number(data?.simUpdatedMs || 0);

  if (!count) {
    card.innerHTML = '<div style="font-weight:850;font-size:15px">📱 SIM / telefon raqamlari</div><div style="color:#8993a2;font-size:12px;margin-top:6px">Faol SIM karta topilmadi yoki ma’lumot hali yuborilmagan.</div>';
    return;
  }

  const rows = sims.map((sim, index) => {
    const number = String(sim?.phoneNumber || '').trim();
    const shown = number || 'Aniqlanmadi';
    const operator = sim?.operator || 'Noma’lum operator';
    const slot = Number.isFinite(Number(sim?.slot)) && Number(sim.slot) >= 0 ? Number(sim.slot) + 1 : index + 1;
    return '<div style="border:1px solid #edf0f4;border-radius:13px;padding:11px 12px;margin-top:8px;background:#fafbfc">'
      + '<div style="display:flex;justify-content:space-between;gap:8px"><b>SIM ' + slot + '</b><span style="font-size:10px;font-weight:800;padding:4px 7px;border-radius:99px;background:#eef2ff;color:#4d6fd6">FAOL</span></div>'
      + '<div style="font-size:16px;font-weight:850;margin-top:5px">' + esc(shown) + '</div>'
      + '<div style="color:#697586;font-size:11px;margin-top:3px">' + esc(operator) + '</div>'
      + '</div>';
  }).join('');

  card.innerHTML = '<div style="display:flex;align-items:center;justify-content:space-between;gap:8px">'
    + '<div><div style="font-weight:850;font-size:15px">📱 SIM / telefon raqamlari</div><div style="color:#697586;font-size:12px;margin-top:3px">Joriy faol SIM: <b>' + count + ' ta</b></div></div>'
    + '<span style="font-size:11px;font-weight:800;color:#16a34a">● Yangilangan</span></div>'
    + rows
    + '<div style="color:#8993a2;font-size:10px;margin-top:9px">' + (updated ? 'Oxirgi tekshiruv: ' + new Date(updated).toLocaleString('uz-UZ') : 'Oxirgi tekshiruv: —') + '</div>';
}

function stop() {
  if (simUnsub) {
    simUnsub();
    simUnsub = null;
  }
}

function watch(childId) {
  const family = familyCode();
  if (!family || !childId) return;
  if (family === selectedFamily && childId === selectedChildId && simUnsub) return;
  stop();
  selectedFamily = family;
  selectedChildId = childId;
  const ref = doc(simDb, 'families', family, 'children', childId);
  simUnsub = onSnapshot(ref, snap => render(snap.exists() ? snap.data() : {}), () => render({}));
}

function refreshSelection() {
  const dashboard = document.getElementById('dashboard');
  if (!dashboard?.classList.contains('active')) return;
  const id = findSelectedChildId();
  if (id) watch(id);
}

const rootObserver = new MutationObserver(() => {
  document.querySelectorAll('#childList .child[data-child-id]').forEach(button => {
    if (button.dataset.simListener === '1') return;
    button.dataset.simListener = '1';
    button.addEventListener('click', () => setTimeout(refreshSelection, 50));
  });
  refreshSelection();
});
rootObserver.observe(document.body, { childList: true, subtree: true, attributes: true, attributeFilter: ['class'] });

setInterval(refreshSelection, 1200);
